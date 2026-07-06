package com.syun.buildassist;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Minecraft 26.1.2 / Fabric client-only building assistant.
 *
 * <p>B toggles the mod. M cycles NONE -> LINE -> AREA. While a mode is active,
 * right-click a block face once to set the first position and once more to queue
 * the selected shape. Placement uses ordinary vanilla client interaction packets,
 * so the server does not need this mod.</p>
 */
public final class BuildAssistClient implements ClientModInitializer {
    private static final int BLOCKS_PER_TICK = 3;
    private static final int MAX_BLOCKS_PER_ACTION = 1024;

    private static KeyMapping toggleKey;
    private static KeyMapping modeKey;

    private static boolean enabled;
    private static BuildMode currentMode = BuildMode.NONE;
    private static final PlacementQueue PLACEMENT_QUEUE = new PlacementQueue();

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
    }

    private static void onEndClientTick(Minecraft client) {
        while (toggleKey.consumeClick()) {
            enabled = !enabled;

            if (!enabled) {
                currentMode = BuildMode.NONE;
                PLACEMENT_QUEUE.clear();
            }

            sendActionBar(client, "BuildAssist: " + (enabled ? "ON" : "OFF"));
        }

        while (modeKey.consumeClick()) {
            if (!enabled) {
                enabled = true;
                sendActionBar(client, "BuildAssist: ON");
            }

            currentMode = currentMode.next();
            PLACEMENT_QUEUE.clear();
            sendActionBar(client, "Mode: " + currentMode.displayName);
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
            sendActionBar(client, "BuildAssist: ブロックを手に持ってください");
            return InteractionResult.FAIL;
        }

        BlockPos targetPos = hitResult.getBlockPos().relative(hitResult.getDirection()).immutable();
        BlockState targetState = level.getBlockState(targetPos);
        if (!targetState.canBeReplaced()) {
            sendActionBar(client, "BuildAssist: 指定位置には設置できません");
            return InteractionResult.FAIL;
        }

        if (!PLACEMENT_QUEUE.hasStart()) {
            PLACEMENT_QUEUE.setStart(targetPos);
            sendActionBar(client, "Start: " + formatPos(targetPos));
            return InteractionResult.FAIL;
        }

        int queued = PLACEMENT_QUEUE.setEnd(targetPos, currentMode);
        sendActionBar(client, "Queued: " + queued + " blocks");
        return InteractionResult.FAIL;
    }

    private static void sendActionBar(Minecraft client, String message) {
        if (client.player != null) {
            client.player.displayClientMessage(Component.literal(message), true);
        }
    }

    private static String formatPos(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    private enum BuildMode {
        NONE("NONE"),
        LINE("LINE"),
        AREA("AREA");

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

    private static final class PlacementQueue {
        private final Deque<BlockPos> queue = new ArrayDeque<>();
        private BlockPos start;
        private List<BlockPos> lastQueuedShape = List.of();

        private void setStart(BlockPos pos) {
            start = pos.immutable();
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
                queue.addLast(shape.get(i));
            }

            lastQueuedShape = new ArrayList<>(queue);
            start = null;
            return limit;
        }

        private void clear() {
            start = null;
            queue.clear();
            lastQueuedShape = List.of();
        }

        private void tick(Minecraft client) {
            if (client.player == null || client.level == null || client.gameMode == null) {
                return;
            }

            int handled = 0;
            while (handled < BLOCKS_PER_TICK && !queue.isEmpty()) {
                BlockPos targetPos = queue.removeFirst();
                tryPlaceBlock(client, targetPos);
                handled++;
            }

            if (queue.isEmpty()) {
                lastQueuedShape = List.of();
            }
        }

        @SuppressWarnings("unused")
        private List<BlockPos> getLastQueuedShape() {
            return Collections.unmodifiableList(lastQueuedShape);
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
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
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

    private static boolean tryPlaceBlock(Minecraft client, BlockPos targetPos) {
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        if (player == null || level == null || client.gameMode == null) {
            return false;
        }

        ItemStack heldStack = player.getMainHandItem();
        if (heldStack.isEmpty() || !(heldStack.getItem() instanceof BlockItem)) {
            return false;
        }

        if (!level.getBlockState(targetPos).canBeReplaced()) {
            return false;
        }

        BlockHitResult placementHit = createPlacementHitResult(level, targetPos);
        if (placementHit == null) {
            return false;
        }

        InteractionResult result = client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, placementHit);
        if (result.consumesAction()) {
            player.swing(InteractionHand.MAIN_HAND);
            return true;
        }

        return false;
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
