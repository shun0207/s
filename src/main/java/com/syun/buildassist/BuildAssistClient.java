package com.syun.buildassist;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Minecraft 26.1.2 / Fabric client-only building assistant.
 *
 * <p>B toggles the mod. M cycles NONE -> LINE -> AREA. Right-click a block
 * face once to choose the start and again to choose the end. Placement uses
 * normal vanilla client interactions, so the server does not need this mod.</p>
 */
public final class BuildAssistClient implements ClientModInitializer {
    private static final Identifier HUD_ID = Identifier.fromNamespaceAndPath("buildassist", "status_hud");

    private static final int MAX_BLOCKS_PER_ACTION = 1024;
    private static final int MAX_RENDERED_QUEUED_BLOCKS = 256;
    private static final int MAX_RETRY_COUNT = 80;
    private static final int PLACE_INTERVAL_TICKS = 2;

    private static final int COLOR_PANEL = 0xB0000000;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_ENABLED = 0xFF55FF55;
    private static final int COLOR_WARNING = 0xFFFFAA00;

    private static final int COLOR_START = ARGB.colorFromFloat(0.42f, 0.15f, 1.00f, 0.20f);
    private static final int COLOR_END = ARGB.colorFromFloat(0.42f, 1.00f, 0.22f, 0.15f);
    private static final int COLOR_PREVIEW = ARGB.colorFromFloat(0.20f, 0.05f, 0.78f, 1.00f);
    private static final int COLOR_QUEUED = ARGB.colorFromFloat(0.16f, 1.00f, 0.60f, 0.05f);
    private static final int COLOR_INVALID = ARGB.colorFromFloat(0.38f, 1.00f, 0.05f, 0.05f);

    private static KeyMapping toggleKey;
    private static KeyMapping modeKey;

    private static boolean enabled;
    private static boolean internalPlacement;
    private static BuildMode currentMode = BuildMode.NONE;
    private static final PlacementQueue PLACEMENT_QUEUE = new PlacementQueue();

    private static long clientTicks;
    private static String statusMessage = "";
    private static long statusUntilTick;

    @Override
    public void onInitializeClient() {
        toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.buildassist.toggle",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_B,
                KeyMapping.Category.MISC));

        modeKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.buildassist.mode",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_M,
                KeyMapping.Category.MISC));

        ClientTickEvents.END_CLIENT_TICK.register(BuildAssistClient::onEndClientTick);
        UseBlockCallback.EVENT.register(BuildAssistClient::onUseBlock);
        HudElementRegistry.addLast(HUD_ID, BuildAssistClient::renderHud);
        LevelRenderEvents.BEFORE_TRANSLUCENT_TERRAIN.register(BuildAssistClient::renderWorldPreview);
    }

    private static void onEndClientTick(Minecraft client) {
        clientTicks++;

        while (toggleKey.consumeClick()) {
            enabled = !enabled;

            if (!enabled) {
                currentMode = BuildMode.NONE;
                PLACEMENT_QUEUE.clear();
                setStatus("BuildAssistをOFFにしました", 60);
            } else {
                setStatus("BuildAssistをONにしました", 60);
            }
        }

        while (modeKey.consumeClick()) {
            if (!enabled) {
                enabled = true;
            }

            currentMode = currentMode.next();
            PLACEMENT_QUEUE.clearSelection();
            setStatus("モード: " + currentMode.displayName, 60);
        }

        if (enabled && currentMode != BuildMode.NONE) {
            PLACEMENT_QUEUE.tick(client);
        }
    }

    private static InteractionResult onUseBlock(
            net.minecraft.world.entity.player.Player player,
            net.minecraft.world.level.Level level,
            InteractionHand hand,
            BlockHitResult hitResult) {

        // MultiPlayerGameMode#useItemOn also invokes this callback. Without this
        // guard, our own automatic placement is interpreted as a new selection
        // click and is cancelled with FAIL before a packet can be sent.
        if (internalPlacement) {
            return InteractionResult.PASS;
        }

        if (!level.isClientSide()
                || hand != InteractionHand.MAIN_HAND
                || !enabled
                || currentMode == BuildMode.NONE) {
            return InteractionResult.PASS;
        }

        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null || player != client.player) {
            return InteractionResult.PASS;
        }

        ItemStack heldStack = player.getItemInHand(hand);
        if (!(heldStack.getItem() instanceof BlockItem)) {
            setStatus("ブロックをメインハンドに持ってください", 100);
            return InteractionResult.FAIL;
        }

        BlockPos targetPos = hitResult.getBlockPos().relative(hitResult.getDirection()).immutable();
        if (!level.getBlockState(targetPos).canBeReplaced()) {
            setStatus("その位置は置き換えできません", 100);
            return InteractionResult.FAIL;
        }

        if (!PLACEMENT_QUEUE.hasStart()) {
            PLACEMENT_QUEUE.setStart(targetPos);
            setStatus("始点: " + formatPos(targetPos) + " / 終点を右クリック", 120);
            return InteractionResult.FAIL;
        }

        int queued = PLACEMENT_QUEUE.setEnd(targetPos, currentMode);
        if (queued <= 0) {
            setStatus("設置対象がありません", 100);
        } else {
            setStatus(queued + "ブロックを設置キューに追加しました", 100);
        }
        return InteractionResult.FAIL;
    }

    private static void renderHud(GuiGraphicsExtractor graphics, net.minecraft.client.DeltaTracker deltaTracker) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || !enabled) {
            return;
        }

        List<String> lines = new ArrayList<>();
        lines.add("BuildAssist: ON");
        lines.add("モード: " + currentMode.displayName);

        if (currentMode == BuildMode.NONE) {
            lines.add("Mキーでモードを選択");
        } else if (PLACEMENT_QUEUE.hasStart()) {
            lines.add("始点: " + formatPos(PLACEMENT_QUEUE.getStart()));
            lines.add("終点を右クリック");
        } else {
            lines.add("始点を右クリック");
        }

        if (PLACEMENT_QUEUE.remainingCount() > 0) {
            lines.add("設置待ち: " + PLACEMENT_QUEUE.remainingCount());
        }

        boolean showStatus = !statusMessage.isEmpty() && clientTicks <= statusUntilTick;
        if (showStatus) {
            lines.add(statusMessage);
        }

        int x = 6;
        int y = 6;
        int lineHeight = client.font.lineHeight + 2;
        int width = 130;
        for (String line : lines) {
            width = Math.max(width, client.font.width(line) + 12);
        }
        int height = lines.size() * lineHeight + 8;

        graphics.fill(x, y, x + width, y + height, COLOR_PANEL);
        for (int i = 0; i < lines.size(); i++) {
            int color = i == 0 ? COLOR_ENABLED : COLOR_TEXT;
            if (showStatus && i == lines.size() - 1) {
                color = COLOR_WARNING;
            }
            graphics.text(client.font, lines.get(i), x + 6, y + 5 + i * lineHeight, color);
        }
    }

    private static void renderWorldPreview(LevelRenderContext context) {
        Minecraft client = Minecraft.getInstance();
        if (!enabled || currentMode == BuildMode.NONE || client.level == null || client.player == null) {
            return;
        }

        BlockPos hover = getHoveredPlacementPos(client);
        BlockPos start = PLACEMENT_QUEUE.getStart();
        List<BlockPos> queued = PLACEMENT_QUEUE.queuedSnapshot(MAX_RENDERED_QUEUED_BLOCKS);

        if (hover == null && start == null && queued.isEmpty()) {
            return;
        }

        Vec3 camera = context.levelState().cameraRenderState.pos;
        PoseStack poseStack = context.poseStack();
        VertexConsumer consumer = context.bufferSource().getBuffer(RenderTypes.debugFilledBox());

        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);

        for (BlockPos pos : queued) {
            drawFilledBox(poseStack, consumer, slightlyInsetBox(pos, 0.035), COLOR_QUEUED);
        }

        if (start != null) {
            drawFilledBox(poseStack, consumer, slightlyInsetBox(start, 0.018), COLOR_START);
        }

        if (hover != null) {
            boolean replaceable = client.level.getBlockState(hover).canBeReplaced();
            drawFilledBox(
                    poseStack,
                    consumer,
                    slightlyInsetBox(hover, 0.012),
                    replaceable ? COLOR_END : COLOR_INVALID);
        }

        if (start != null && hover != null && client.level.getBlockState(hover).canBeReplaced()) {
            if (currentMode == BuildMode.AREA) {
                drawFilledBox(poseStack, consumer, areaBox(start, hover, 0.045), COLOR_PREVIEW);
            } else if (currentMode == BuildMode.LINE) {
                List<BlockPos> preview = PlacementQueue.createLine(start, hover);
                int renderLimit = Math.min(preview.size(), MAX_RENDERED_QUEUED_BLOCKS);
                for (int i = 0; i < renderLimit; i++) {
                    drawFilledBox(poseStack, consumer, slightlyInsetBox(preview.get(i), 0.06), COLOR_PREVIEW);
                }
            }
        }

        poseStack.popPose();
    }

    private static BlockPos getHoveredPlacementPos(Minecraft client) {
        HitResult hit = client.hitResult;
        if (!(hit instanceof BlockHitResult blockHitResult)
                || blockHitResult.getType() == HitResult.Type.MISS) {
            return null;
        }
        return blockHitResult.getBlockPos().relative(blockHitResult.getDirection()).immutable();
    }

    private static AABB slightlyInsetBox(BlockPos pos, double inset) {
        return new AABB(
                pos.getX() + inset,
                pos.getY() + inset,
                pos.getZ() + inset,
                pos.getX() + 1.0 - inset,
                pos.getY() + 1.0 - inset,
                pos.getZ() + 1.0 - inset);
    }

    private static AABB areaBox(BlockPos first, BlockPos second, double inset) {
        int minX = Math.min(first.getX(), second.getX());
        int minY = Math.min(first.getY(), second.getY());
        int minZ = Math.min(first.getZ(), second.getZ());
        int maxX = Math.max(first.getX(), second.getX());
        int maxY = Math.max(first.getY(), second.getY());
        int maxZ = Math.max(first.getZ(), second.getZ());
        return new AABB(
                minX + inset,
                minY + inset,
                minZ + inset,
                maxX + 1.0 - inset,
                maxY + 1.0 - inset,
                maxZ + 1.0 - inset);
    }

    private static void drawFilledBox(PoseStack poseStack, VertexConsumer vertexConsumer, AABB box, int color) {
        Matrix4f matrix = poseStack.last().pose();

        vertexConsumer.addVertex(matrix, (float) box.minX, (float) box.minY, (float) box.minZ).setColor(color);
        vertexConsumer.addVertex(matrix, (float) box.maxX, (float) box.minY, (float) box.minZ).setColor(color);
        vertexConsumer.addVertex(matrix, (float) box.maxX, (float) box.maxY, (float) box.minZ).setColor(color);
        vertexConsumer.addVertex(matrix, (float) box.minX, (float) box.maxY, (float) box.minZ).setColor(color);

        vertexConsumer.addVertex(matrix, (float) box.maxX, (float) box.minY, (float) box.maxZ).setColor(color);
        vertexConsumer.addVertex(matrix, (float) box.minX, (float) box.minY, (float) box.maxZ).setColor(color);
        vertexConsumer.addVertex(matrix, (float) box.minX, (float) box.maxY, (float) box.maxZ).setColor(color);
        vertexConsumer.addVertex(matrix, (float) box.maxX, (float) box.maxY, (float) box.maxZ).setColor(color);

        vertexConsumer.addVertex(matrix, (float) box.minX, (float) box.minY, (float) box.maxZ).setColor(color);
        vertexConsumer.addVertex(matrix, (float) box.minX, (float) box.minY, (float) box.minZ).setColor(color);
        vertexConsumer.addVertex(matrix, (float) box.minX, (float) box.maxY, (float) box.minZ).setColor(color);
        vertexConsumer.addVertex(matrix, (float) box.minX, (float) box.maxY, (float) box.maxZ).setColor(color);

        vertexConsumer.addVertex(matrix, (float) box.maxX, (float) box.minY, (float) box.minZ).setColor(color);
        vertexConsumer.addVertex(matrix, (float) box.maxX, (float) box.minY, (float) box.maxZ).setColor(color);
        vertexConsumer.addVertex(matrix, (float) box.maxX, (float) box.maxY, (float) box.maxZ).setColor(color);
        vertexConsumer.addVertex(matrix, (float) box.maxX, (float) box.maxY, (float) box.minZ).setColor(color);

        vertexConsumer.addVertex(matrix, (float) box.minX, (float) box.maxY, (float) box.minZ).setColor(color);
        vertexConsumer.addVertex(matrix, (float) box.maxX, (float) box.maxY, (float) box.minZ).setColor(color);
        vertexConsumer.addVertex(matrix, (float) box.maxX, (float) box.maxY, (float) box.maxZ).setColor(color);
        vertexConsumer.addVertex(matrix, (float) box.minX, (float) box.maxY, (float) box.maxZ).setColor(color);

        vertexConsumer.addVertex(matrix, (float) box.minX, (float) box.minY, (float) box.maxZ).setColor(color);
        vertexConsumer.addVertex(matrix, (float) box.maxX, (float) box.minY, (float) box.maxZ).setColor(color);
        vertexConsumer.addVertex(matrix, (float) box.maxX, (float) box.minY, (float) box.minZ).setColor(color);
        vertexConsumer.addVertex(matrix, (float) box.minX, (float) box.minY, (float) box.minZ).setColor(color);
    }

    private static void setStatus(String message, int durationTicks) {
        statusMessage = message;
        statusUntilTick = clientTicks + durationTicks;
    }

    private static String formatPos(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    private enum BuildMode {
        NONE("未選択"),
        LINE("直線"),
        AREA("範囲");

        private final String displayName;

        BuildMode(String displayName) {
            this.displayName = displayName;
        }

        private BuildMode next() {
            return switch (this) {
                case NONE -> LINE;
                case LINE -> AREA;
                case AREA -> NONE;
            };
        }
    }

    private enum PlaceResult {
        SUCCESS,
        RETRY,
        SKIP
    }

    private static final class QueuedPlacement {
        private final BlockPos pos;
        private int retries;

        private QueuedPlacement(BlockPos pos) {
            this.pos = pos.immutable();
        }
    }

    private static final class PlacementQueue {
        private final Deque<QueuedPlacement> queue = new ArrayDeque<>();
        private BlockPos start;
        private long nextPlacementTick;

        private void setStart(BlockPos pos) {
            start = pos.immutable();
        }

        private BlockPos getStart() {
            return start;
        }

        private boolean hasStart() {
            return start != null;
        }

        private int setEnd(BlockPos end, BuildMode mode) {
            if (start == null) {
                return 0;
            }

            List<BlockPos> shape = mode == BuildMode.AREA
                    ? createArea(start, end.immutable())
                    : createLine(start, end.immutable());

            queue.clear();
            int limit = Math.min(shape.size(), MAX_BLOCKS_PER_ACTION);
            for (int i = 0; i < limit; i++) {
                queue.addLast(new QueuedPlacement(shape.get(i)));
            }

            start = null;
            nextPlacementTick = clientTicks;
            return limit;
        }

        private void clearSelection() {
            start = null;
        }

        private void clear() {
            start = null;
            queue.clear();
            nextPlacementTick = 0;
        }

        private int remainingCount() {
            return queue.size();
        }

        private List<BlockPos> queuedSnapshot(int limit) {
            List<BlockPos> positions = new ArrayList<>();
            int count = 0;
            for (QueuedPlacement placement : queue) {
                positions.add(placement.pos);
                count++;
                if (count >= limit) {
                    break;
                }
            }
            return positions;
        }

        private void tick(Minecraft client) {
            if (client.player == null || client.level == null || client.gameMode == null || queue.isEmpty()) {
                return;
            }
            if (clientTicks < nextPlacementTick) {
                return;
            }

            ItemStack heldStack = client.player.getMainHandItem();
            if (heldStack.isEmpty() || !(heldStack.getItem() instanceof BlockItem)) {
                setStatus("設置を一時停止: ブロックを持ってください", 40);
                nextPlacementTick = clientTicks + 10;
                return;
            }

            QueuedPlacement placement = queue.removeFirst();
            PlaceResult result = tryPlaceBlock(client, placement.pos);

            if (result == PlaceResult.RETRY) {
                placement.retries++;
                if (placement.retries <= MAX_RETRY_COUNT) {
                    queue.addLast(placement);
                }
            }

            nextPlacementTick = clientTicks + PLACE_INTERVAL_TICKS;

            if (queue.isEmpty()) {
                setStatus("設置処理が完了しました", 80);
            }
        }

        private static List<BlockPos> createLine(BlockPos start, BlockPos end) {
            int dx = end.getX() - start.getX();
            int dy = end.getY() - start.getY();
            int dz = end.getZ() - start.getZ();
            int steps = Math.max(Math.max(Math.abs(dx), Math.abs(dy)), Math.abs(dz));

            if (steps == 0) {
                return List.of(start.immutable());
            }

            Set<BlockPos> positions = new LinkedHashSet<>();
            for (int i = 0; i <= steps; i++) {
                double t = (double) i / (double) steps;
                int x = start.getX() + (int) Math.round(dx * t);
                int y = start.getY() + (int) Math.round(dy * t);
                int z = start.getZ() + (int) Math.round(dz * t);
                positions.add(new BlockPos(x, y, z));
            }

            return new ArrayList<>(positions);
        }

        private static List<BlockPos> createArea(BlockPos start, BlockPos end) {
            int minX = Math.min(start.getX(), end.getX());
            int minY = Math.min(start.getY(), end.getY());
            int minZ = Math.min(start.getZ(), end.getZ());
            int maxX = Math.max(start.getX(), end.getX());
            int maxY = Math.max(start.getY(), end.getY());
            int maxZ = Math.max(start.getZ(), end.getZ());

            List<BlockPos> positions = new ArrayList<>();
            outer:
            for (int y = minY; y <= maxY; y++) {
                for (int x = minX; x <= maxX; x++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        positions.add(new BlockPos(x, y, z));
                        if (positions.size() >= MAX_BLOCKS_PER_ACTION) {
                            break outer;
                        }
                    }
                }
            }
            return positions;
        }
    }

    private static PlaceResult tryPlaceBlock(Minecraft client, BlockPos targetPos) {
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        if (player == null || level == null || client.gameMode == null) {
            return PlaceResult.RETRY;
        }

        if (!level.getBlockState(targetPos).canBeReplaced()) {
            return PlaceResult.SKIP;
        }

        BlockHitResult placementHit = createPlacementHitResult(level, targetPos);
        if (placementHit == null) {
            return PlaceResult.RETRY;
        }

        InteractionResult result;
        internalPlacement = true;
        try {
            result = client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, placementHit);
        } finally {
            internalPlacement = false;
        }

        if (result.consumesAction()) {
            player.swing(InteractionHand.MAIN_HAND);
            return PlaceResult.SUCCESS;
        }

        return PlaceResult.RETRY;
    }

    private static BlockHitResult createPlacementHitResult(ClientLevel level, BlockPos targetPos) {
        Direction[] priority = {
                Direction.DOWN,
                Direction.UP,
                Direction.NORTH,
                Direction.SOUTH,
                Direction.WEST,
                Direction.EAST
        };

        for (Direction direction : priority) {
            BlockPos neighborPos = targetPos.relative(direction);
            BlockState neighborState = level.getBlockState(neighborPos);
            if (neighborState.canBeReplaced()) {
                continue;
            }

            Direction clickedFace = direction.getOpposite();
            Vec3 hitPosition = Vec3.atCenterOf(neighborPos).add(
                    clickedFace.getStepX() * 0.5,
                    clickedFace.getStepY() * 0.5,
                    clickedFace.getStepZ() * 0.5);

            return new BlockHitResult(hitPosition, clickedFace, neighborPos, false);
        }

        return null;
    }
}
