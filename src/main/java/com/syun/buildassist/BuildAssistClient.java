package com.syun.buildassist;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
import net.fabricmc.loader.api.FabricLoader;
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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Minecraft 26.1.2 / Fabric building assistant.
 *
 * <p>The mod remains client-only. Every placement is sent as an ordinary
 * vanilla block-use action, so server reach, protection and anti-cheat rules
 * are still respected.</p>
 */
public final class BuildAssistClient implements ClientModInitializer {
    private static final Identifier HUD_ID = Identifier.fromNamespaceAndPath("buildassist", "status_hud");

    private static final int MAX_BLOCKS_PER_ACTION = 1024;
    private static final int MAX_RENDERED_BLOCKS = 256;
    private static final int PLACE_INTERVAL_TICKS = 2;
    private static final int FAILED_RETRY_DELAY_TICKS = 20;
    private static final int RADIAL_OPEN_TICKS = 5;

    private static final int COLOR_PANEL = 0xB8000000;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_ENABLED = 0xFF55FF55;
    private static final int COLOR_WARNING = 0xFFFFAA00;
    private static final int COLOR_SELECTED = 0xE63399FF;
    private static final int COLOR_UNSELECTED = 0xD0202020;

    private static final int COLOR_START = ARGB.colorFromFloat(0.42f, 0.15f, 1.00f, 0.20f);
    private static final int COLOR_END = ARGB.colorFromFloat(0.42f, 0.15f, 0.85f, 1.00f);
    private static final int COLOR_READY = ARGB.colorFromFloat(0.24f, 0.10f, 1.00f, 0.20f);
    private static final int COLOR_OUT_OF_RANGE = ARGB.colorFromFloat(0.32f, 1.00f, 0.08f, 0.08f);
    private static final int COLOR_WAITING_SUPPORT = ARGB.colorFromFloat(0.30f, 1.00f, 0.72f, 0.05f);
    private static final int COLOR_INVALID = ARGB.colorFromFloat(0.42f, 0.65f, 0.00f, 0.00f);
    private static final int COLOR_WRONG_ITEM = ARGB.colorFromFloat(0.26f, 0.72f, 0.15f, 1.00f);
    private static final int COLOR_BLOCKED = ARGB.colorFromFloat(0.30f, 0.55f, 0.55f, 0.55f);

    private static final BuildMode[] RADIAL_MODES = {
            BuildMode.LINE,
            BuildMode.FLOOR,
            BuildMode.WALL,
            BuildMode.CUBOID,
            BuildMode.HOLLOW_BOX,
            BuildMode.CIRCLE,
            BuildMode.CYLINDER,
            BuildMode.SPHERE,
            BuildMode.STAIRS,
            BuildMode.SCHEMATIC
    };

    private static KeyMapping toggleKey;
    private static KeyMapping modeKey;
    private static KeyMapping radialKey;

    private static boolean enabled;
    private static boolean internalPlacement;
    private static BuildMode currentMode = BuildMode.NONE;
    private static final PlacementQueue PLACEMENT_QUEUE = new PlacementQueue();
    private static final SchematicAssistant SCHEMATIC_ASSISTANT = new SchematicAssistant();

    private static long clientTicks;
    private static String statusMessage = "";
    private static long statusUntilTick;

    private static boolean modeKeyWasDown;
    private static int modeKeyHeldTicks;
    private static boolean radialKeyWasDown;
    private static int radialKeyHeldTicks;
    private static boolean radialMenuOpen;
    private static int radialHoveredIndex = -1;

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

        radialKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.buildassist.radial",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_LEFT_ALT,
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
                SCHEMATIC_ASSISTANT.reset();
                closeRadialMenu(client, false);
                setStatus("BuildAssistをOFFにしました", 60);
            } else {
                setStatus("BuildAssistをONにしました", 60);
            }
        }

        handleModeKey(client);
        handleRadialKey(client);

        if (!enabled || radialMenuOpen || client.player == null || client.level == null) {
            return;
        }

        if (currentMode == BuildMode.SCHEMATIC) {
            SCHEMATIC_ASSISTANT.tick(client);
        } else if (currentMode != BuildMode.NONE) {
            PLACEMENT_QUEUE.tick(client);
        }
    }

    private static void handleModeKey(Minecraft client) {
        boolean down = modeKey.isDown();
        if (down) {
            modeKeyHeldTicks++;
        }

        if (!down && modeKeyWasDown) {
            if (modeKeyHeldTicks < RADIAL_OPEN_TICKS && !radialMenuOpen) {
                enabled = true;
                selectMode(currentMode.next());
            }
            modeKeyHeldTicks = 0;
        }

        modeKeyWasDown = down;
    }

    private static void handleRadialKey(Minecraft client) {
        boolean down = radialKey.isDown();

        if (down) {
            radialKeyHeldTicks++;
            if (!radialMenuOpen && radialKeyHeldTicks >= RADIAL_OPEN_TICKS) {
                enabled = true;
                openRadialMenu(client);
            }
        }

        if (radialMenuOpen) {
            updateRadialSelection(client, 0, 0);
        }

        if (!down && radialKeyWasDown) {
            if (radialMenuOpen) {
                closeRadialMenu(client, true);
            }
            radialKeyHeldTicks = 0;
        }

        radialKeyWasDown = down;
    }

    private static void selectMode(BuildMode mode) {
        currentMode = mode;
        PLACEMENT_QUEUE.clearSelection();
        SCHEMATIC_ASSISTANT.resetTransientState();
        setStatus("モード: " + currentMode.displayName, 60);
    }

    private static void openRadialMenu(Minecraft client) {
        if (radialMenuOpen) {
            return;
        }

        radialMenuOpen = true;
        radialHoveredIndex = indexOfMode(currentMode);

        try {
            client.mouseHandler.releaseMouse();
            long window = client.getWindow().handle();
            int[] width = {1};
            int[] height = {1};
            GLFW.glfwGetWindowSize(window, width, height);
            GLFW.glfwSetCursorPos(window, width[0] / 2.0, height[0] / 2.0);
        } catch (Throwable ignored) {
            // The wheel can still be selected with the current cursor position.
        }
    }

    private static void closeRadialMenu(Minecraft client, boolean applySelection) {
        if (!radialMenuOpen) {
            return;
        }

        if (applySelection && radialHoveredIndex >= 0 && radialHoveredIndex < RADIAL_MODES.length) {
            selectMode(RADIAL_MODES[radialHoveredIndex]);
        }

        radialMenuOpen = false;
        radialHoveredIndex = -1;

        try {
            client.mouseHandler.grabMouse();
        } catch (Throwable ignored) {
        }
    }

    private static int indexOfMode(BuildMode mode) {
        for (int i = 0; i < RADIAL_MODES.length; i++) {
            if (RADIAL_MODES[i] == mode) {
                return i;
            }
        }
        return -1;
    }

    private static void updateRadialSelection(Minecraft client, int guiWidth, int guiHeight) {
        try {
            long window = client.getWindow().handle();
            int[] width = {1};
            int[] height = {1};
            double[] mouseX = {0};
            double[] mouseY = {0};
            GLFW.glfwGetWindowSize(window, width, height);
            GLFW.glfwGetCursorPos(window, mouseX, mouseY);

            double centerX = width[0] / 2.0;
            double centerY = height[0] / 2.0;
            double dx = mouseX[0] - centerX;
            double dy = mouseY[0] - centerY;
            double distance = Math.sqrt(dx * dx + dy * dy);

            if (distance < Math.min(width[0], height[0]) * 0.035) {
                radialHoveredIndex = -1;
                return;
            }

            double angle = Math.atan2(dy, dx) + Math.PI / 2.0;
            if (angle < 0) {
                angle += Math.PI * 2.0;
            }
            double sectorSize = Math.PI * 2.0 / RADIAL_MODES.length;
            radialHoveredIndex = (int) Math.floor((angle + sectorSize / 2.0) / sectorSize) % RADIAL_MODES.length;
        } catch (Throwable ignored) {
            radialHoveredIndex = indexOfMode(currentMode);
        }
    }

    private static InteractionResult onUseBlock(
            net.minecraft.world.entity.player.Player player,
            Level level,
            InteractionHand hand,
            BlockHitResult hitResult) {

        if (internalPlacement) {
            return InteractionResult.PASS;
        }

        if (!level.isClientSide()
                || hand != InteractionHand.MAIN_HAND
                || !enabled
                || currentMode == BuildMode.NONE
                || currentMode == BuildMode.SCHEMATIC
                || radialMenuOpen) {
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
            setStatus(queued + "ブロックを設置待ちに追加しました", 100);
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
        lines.add("左Alt長押し: 円形メニュー");

        if (currentMode == BuildMode.SCHEMATIC) {
            SchematicStats stats = SCHEMATIC_ASSISTANT.getStats();
            lines.add("Litematica: " + stats.statusText());
            if (stats.schematicLoaded()) {
                lines.add("周辺未設置: " + stats.missing());
                lines.add("手持ち一致: " + stats.matchingHeld());
                lines.add("現在設置可能: " + stats.ready());
                if (stats.wrongItem() > 0) {
                    lines.add("別ブロックが必要: " + stats.wrongItem());
                }
                if (stats.blocked() > 0) {
                    lines.add("既存ブロック競合: " + stats.blocked());
                }
            }
        } else {
            if (currentMode == BuildMode.NONE) {
                lines.add("Mキーまたは左Altでモード選択");
            } else if (PLACEMENT_QUEUE.hasStart()) {
                lines.add("始点: " + formatPos(PLACEMENT_QUEUE.getStart()));
                lines.add("終点を右クリック");
            } else {
                lines.add("始点を右クリック");
            }

            QueueStats stats = PLACEMENT_QUEUE.getStats();
            if (stats.remaining() > 0) {
                lines.add("未設置: " + stats.remaining());
                lines.add("現在設置可能: " + stats.ready());
                lines.add("範囲外: " + stats.outOfRange());
                if (stats.waitingSupport() > 0) {
                    lines.add("支え待ち: " + stats.waitingSupport());
                }
            }
        }

        boolean showStatus = !statusMessage.isEmpty() && clientTicks <= statusUntilTick;
        if (showStatus) {
            lines.add(statusMessage);
        }

        int x = 6;
        int y = 6;
        int lineHeight = client.font.lineHeight + 2;
        int width = 165;
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

        if (radialMenuOpen) {
            renderRadialMenu(graphics, client);
        }
    }

    private static void renderRadialMenu(GuiGraphicsExtractor graphics, Minecraft client) {
        updateRadialSelection(client, graphics.guiWidth(), graphics.guiHeight());

        int centerX = graphics.guiWidth() / 2;
        int centerY = graphics.guiHeight() / 2;
        int radius = Math.min(112, Math.min(graphics.guiWidth(), graphics.guiHeight()) / 3);

        for (int yy = -radius; yy <= radius; yy += 4) {
            int span = (int) Math.sqrt(Math.max(0, radius * radius - yy * yy));
            graphics.fill(centerX - span, centerY + yy, centerX + span, centerY + yy + 4, 0xB0181818);
        }

        int innerRadius = 34;
        for (int yy = -innerRadius; yy <= innerRadius; yy += 3) {
            int span = (int) Math.sqrt(Math.max(0, innerRadius * innerRadius - yy * yy));
            graphics.fill(centerX - span, centerY + yy, centerX + span, centerY + yy + 3, 0xE0000000);
        }

        double sectorSize = Math.PI * 2.0 / RADIAL_MODES.length;
        int buttonRadius = Math.max(65, radius - 28);

        for (int i = 0; i < RADIAL_MODES.length; i++) {
            double angle = i * sectorSize - Math.PI / 2.0;
            int bx = centerX + (int) Math.round(Math.cos(angle) * buttonRadius);
            int by = centerY + (int) Math.round(Math.sin(angle) * buttonRadius);
            boolean selected = i == radialHoveredIndex;
            int buttonWidth = Math.max(42, client.font.width(RADIAL_MODES[i].shortName) + 12);
            int color = selected ? COLOR_SELECTED : COLOR_UNSELECTED;
            graphics.fill(bx - buttonWidth / 2, by - 10, bx + buttonWidth / 2, by + 10, color);
            graphics.centeredText(client.font, RADIAL_MODES[i].shortName, bx, by - client.font.lineHeight / 2, COLOR_TEXT);
        }

        String centerText = radialHoveredIndex >= 0
                ? RADIAL_MODES[radialHoveredIndex].displayName
                : "キャンセル";
        graphics.centeredText(client.font, centerText, centerX, centerY - client.font.lineHeight, COLOR_TEXT);
        graphics.centeredText(client.font, "左Altを離して決定", centerX, centerY + 4, 0xFFCCCCCC);
    }

    private static void renderWorldPreview(LevelRenderContext context) {
        Minecraft client = Minecraft.getInstance();
        if (!enabled || currentMode == BuildMode.NONE || client.level == null || client.player == null) {
            return;
        }

        Vec3 camera = context.levelState().cameraRenderState.pos;
        PoseStack poseStack = context.poseStack();
        VertexConsumer consumer = context.bufferSource().getBuffer(RenderTypes.debugFilledBox());

        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);

        if (currentMode == BuildMode.SCHEMATIC) {
            for (PreviewBlock preview : SCHEMATIC_ASSISTANT.getPreviewBlocks()) {
                drawFilledBox(poseStack, consumer, slightlyInsetBox(preview.pos(), 0.045), preview.color());
            }
            poseStack.popPose();
            return;
        }

        BlockPos hover = getHoveredPlacementPos(client);
        BlockPos start = PLACEMENT_QUEUE.getStart();
        List<BlockPos> queued = PLACEMENT_QUEUE.queuedSnapshot(client, MAX_RENDERED_BLOCKS);

        for (BlockPos pos : queued) {
            PlacementState state = classifyPlacement(client, pos);
            if (state != PlacementState.FILLED) {
                drawFilledBox(poseStack, consumer, slightlyInsetBox(pos, 0.035), colorForPlacementState(state));
            }
        }

        if (start != null) {
            drawFilledBox(poseStack, consumer, slightlyInsetBox(start, 0.018), COLOR_START);
        }

        if (hover != null) {
            PlacementState hoverState = classifyPlacement(client, hover);
            int hoverColor = switch (hoverState) {
                case READY -> COLOR_END;
                case OUT_OF_RANGE -> COLOR_OUT_OF_RANGE;
                case WAITING_SUPPORT -> COLOR_WAITING_SUPPORT;
                case FILLED -> COLOR_INVALID;
            };
            drawFilledBox(poseStack, consumer, slightlyInsetBox(hover, 0.012), hoverColor);
        }

        if (start != null && hover != null && client.level.getBlockState(hover).canBeReplaced()) {
            List<BlockPos> preview = ShapeGenerator.create(currentMode, start, hover, MAX_RENDERED_BLOCKS);
            for (BlockPos pos : preview) {
                PlacementState state = classifyPlacement(client, pos);
                if (state != PlacementState.FILLED) {
                    drawFilledBox(poseStack, consumer, slightlyInsetBox(pos, 0.06), colorForPlacementState(state));
                }
            }
        }

        poseStack.popPose();
    }

    private static int colorForPlacementState(PlacementState state) {
        return switch (state) {
            case READY -> COLOR_READY;
            case OUT_OF_RANGE -> COLOR_OUT_OF_RANGE;
            case WAITING_SUPPORT -> COLOR_WAITING_SUPPORT;
            case FILLED -> COLOR_INVALID;
        };
    }

    private static PlacementState classifyPlacement(Minecraft client, BlockPos targetPos) {
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        if (player == null || level == null) {
            return PlacementState.WAITING_SUPPORT;
        }

        if (!level.getBlockState(targetPos).canBeReplaced()) {
            return PlacementState.FILLED;
        }

        BlockHitResult hitResult = createPlacementHitResult(level, targetPos);
        if (hitResult == null) {
            return PlacementState.WAITING_SUPPORT;
        }

        if (!isWithinPlacementReach(player, hitResult)) {
            return PlacementState.OUT_OF_RANGE;
        }

        return PlacementState.READY;
    }

    private static boolean isWithinPlacementReach(LocalPlayer player, BlockHitResult hitResult) {
        double reach = Math.max(1.0, player.blockInteractionRange() - 0.15);
        return player.getEyePosition().distanceToSqr(hitResult.getLocation()) <= reach * reach;
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
        NONE("未選択", "なし"),
        LINE("直線", "直線"),
        FLOOR("床・平面", "床"),
        WALL("壁", "壁"),
        CUBOID("直方体", "直方体"),
        HOLLOW_BOX("中空の箱", "枠"),
        CIRCLE("円", "円"),
        CYLINDER("円柱", "円柱"),
        SPHERE("球体", "球"),
        STAIRS("階段", "階段"),
        SCHEMATIC("Litematica設計図", "設計図");

        private final String displayName;
        private final String shortName;

        BuildMode(String displayName, String shortName) {
            this.displayName = displayName;
            this.shortName = shortName;
        }

        private BuildMode next() {
            int index = indexOfMode(this);
            if (index < 0 || index + 1 >= RADIAL_MODES.length) {
                return RADIAL_MODES[0];
            }
            return RADIAL_MODES[index + 1];
        }
    }

    private enum PlacementState {
        READY,
        OUT_OF_RANGE,
        WAITING_SUPPORT,
        FILLED
    }

    private enum PlaceResult {
        SUCCESS,
        RETRY,
        SKIP
    }

    private record QueueStats(int remaining, int ready, int outOfRange, int waitingSupport) {
        private static final QueueStats EMPTY = new QueueStats(0, 0, 0, 0);
    }

    private record PreviewBlock(BlockPos pos, int color) {
    }

    private record SchematicStats(
            boolean litematicaAvailable,
            boolean schematicLoaded,
            int missing,
            int matchingHeld,
            int ready,
            int wrongItem,
            int blocked,
            String error) {

        private static final SchematicStats EMPTY = new SchematicStats(false, false, 0, 0, 0, 0, 0, "");

        private String statusText() {
            if (!litematicaAvailable) {
                return "未導入または非対応";
            }
            if (!error.isEmpty()) {
                return "エラー";
            }
            return schematicLoaded ? "連携中" : "設計図なし";
        }
    }

    private static final class QueuedPlacement {
        private final BlockPos pos;
        private long retryAfterTick;

        private QueuedPlacement(BlockPos pos) {
            this.pos = pos.immutable();
        }
    }

    private static final class PlacementQueue {
        private final List<QueuedPlacement> queue = new ArrayList<>();
        private BlockPos start;
        private long nextPlacementTick;
        private QueueStats stats = QueueStats.EMPTY;

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

            List<BlockPos> shape = ShapeGenerator.create(mode, start, end.immutable(), MAX_BLOCKS_PER_ACTION);
            queue.clear();
            for (BlockPos pos : shape) {
                queue.add(new QueuedPlacement(pos));
            }

            start = null;
            nextPlacementTick = clientTicks;
            stats = new QueueStats(queue.size(), 0, 0, queue.size());
            return queue.size();
        }

        private void clearSelection() {
            start = null;
        }

        private void clear() {
            start = null;
            queue.clear();
            nextPlacementTick = 0;
            stats = QueueStats.EMPTY;
        }

        private QueueStats getStats() {
            return stats;
        }

        private List<BlockPos> queuedSnapshot(Minecraft client, int limit) {
            if (client.player == null || queue.isEmpty()) {
                return List.of();
            }

            Vec3 eyePosition = client.player.getEyePosition();
            List<QueuedPlacement> sorted = new ArrayList<>(queue);
            sorted.sort(Comparator.comparingDouble(
                    placement -> eyePosition.distanceToSqr(Vec3.atCenterOf(placement.pos))));

            List<BlockPos> positions = new ArrayList<>();
            int renderLimit = Math.min(sorted.size(), limit);
            for (int i = 0; i < renderLimit; i++) {
                positions.add(sorted.get(i).pos);
            }
            return positions;
        }

        private void tick(Minecraft client) {
            if (client.player == null || client.level == null || client.gameMode == null) {
                stats = QueueStats.EMPTY;
                return;
            }

            cleanupCompleted(client.level);
            refreshStats(client);

            if (queue.isEmpty() || clientTicks < nextPlacementTick) {
                return;
            }

            ItemStack heldStack = client.player.getMainHandItem();
            if (heldStack.isEmpty() || !(heldStack.getItem() instanceof BlockItem)) {
                setStatus("設置を一時停止: ブロックを持ってください", 40);
                nextPlacementTick = clientTicks + 10;
                return;
            }

            QueuedPlacement placement = findNearestReadyPlacement(client);
            if (placement == null) {
                nextPlacementTick = clientTicks + PLACE_INTERVAL_TICKS;
                return;
            }

            PlaceResult result = tryPlaceBlock(client, placement.pos);
            if (result == PlaceResult.SUCCESS || result == PlaceResult.SKIP) {
                queue.remove(placement);
            } else {
                placement.retryAfterTick = clientTicks + FAILED_RETRY_DELAY_TICKS;
            }

            cleanupCompleted(client.level);
            refreshStats(client);
            nextPlacementTick = clientTicks + PLACE_INTERVAL_TICKS;

            if (queue.isEmpty()) {
                setStatus("設置処理が完了しました", 80);
            }
        }

        private void cleanupCompleted(ClientLevel level) {
            Iterator<QueuedPlacement> iterator = queue.iterator();
            while (iterator.hasNext()) {
                QueuedPlacement placement = iterator.next();
                if (!level.getBlockState(placement.pos).canBeReplaced()) {
                    iterator.remove();
                }
            }
        }

        private void refreshStats(Minecraft client) {
            int ready = 0;
            int outOfRange = 0;
            int waitingSupport = 0;

            for (QueuedPlacement placement : queue) {
                PlacementState state = classifyPlacement(client, placement.pos);
                switch (state) {
                    case READY -> ready++;
                    case OUT_OF_RANGE -> outOfRange++;
                    case WAITING_SUPPORT -> waitingSupport++;
                    case FILLED -> {
                    }
                }
            }

            stats = new QueueStats(queue.size(), ready, outOfRange, waitingSupport);
        }

        private QueuedPlacement findNearestReadyPlacement(Minecraft client) {
            LocalPlayer player = client.player;
            ClientLevel level = client.level;
            if (player == null || level == null) {
                return null;
            }

            Vec3 eyePosition = player.getEyePosition();
            QueuedPlacement nearest = null;
            double nearestDistanceSqr = Double.MAX_VALUE;

            for (QueuedPlacement placement : queue) {
                if (placement.retryAfterTick > clientTicks
                        || !level.getBlockState(placement.pos).canBeReplaced()) {
                    continue;
                }

                BlockHitResult hitResult = createPlacementHitResult(level, placement.pos);
                if (hitResult == null || !isWithinPlacementReach(player, hitResult)) {
                    continue;
                }

                double distanceSqr = eyePosition.distanceToSqr(hitResult.getLocation());
                if (distanceSqr < nearestDistanceSqr) {
                    nearestDistanceSqr = distanceSqr;
                    nearest = placement;
                }
            }

            return nearest;
        }
    }

    private static final class ShapeGenerator {
        private ShapeGenerator() {
        }

        private static List<BlockPos> create(BuildMode mode, BlockPos start, BlockPos end, int limit) {
            LinkedHashSet<BlockPos> positions = new LinkedHashSet<>();

            switch (mode) {
                case LINE -> addLine(positions, start, end, limit);
                case FLOOR -> addFloor(positions, start, end, limit);
                case WALL -> addWall(positions, start, end, limit);
                case CUBOID -> addCuboid(positions, start, end, false, limit);
                case HOLLOW_BOX -> addCuboid(positions, start, end, true, limit);
                case CIRCLE -> addCircle(positions, start, end, limit);
                case CYLINDER -> addCylinder(positions, start, end, limit);
                case SPHERE -> addSphere(positions, start, end, limit);
                case STAIRS -> addStairs(positions, start, end, limit);
                default -> {
                }
            }

            return new ArrayList<>(positions);
        }

        private static boolean add(Set<BlockPos> positions, BlockPos pos, int limit) {
            if (positions.size() >= limit) {
                return false;
            }
            positions.add(pos.immutable());
            return positions.size() < limit;
        }

        private static void addLine(Set<BlockPos> positions, BlockPos start, BlockPos end, int limit) {
            int dx = end.getX() - start.getX();
            int dy = end.getY() - start.getY();
            int dz = end.getZ() - start.getZ();
            int steps = Math.max(Math.max(Math.abs(dx), Math.abs(dy)), Math.abs(dz));
            if (steps == 0) {
                add(positions, start, limit);
                return;
            }

            for (int i = 0; i <= steps && positions.size() < limit; i++) {
                double t = (double) i / steps;
                add(positions, new BlockPos(
                        start.getX() + (int) Math.round(dx * t),
                        start.getY() + (int) Math.round(dy * t),
                        start.getZ() + (int) Math.round(dz * t)), limit);
            }
        }

        private static void addFloor(Set<BlockPos> positions, BlockPos start, BlockPos end, int limit) {
            int minX = Math.min(start.getX(), end.getX());
            int maxX = Math.max(start.getX(), end.getX());
            int minZ = Math.min(start.getZ(), end.getZ());
            int maxZ = Math.max(start.getZ(), end.getZ());
            for (int x = minX; x <= maxX && positions.size() < limit; x++) {
                for (int z = minZ; z <= maxZ && positions.size() < limit; z++) {
                    add(positions, new BlockPos(x, start.getY(), z), limit);
                }
            }
        }

        private static void addWall(Set<BlockPos> positions, BlockPos start, BlockPos end, int limit) {
            int minY = Math.min(start.getY(), end.getY());
            int maxY = Math.max(start.getY(), end.getY());
            int dx = Math.abs(end.getX() - start.getX());
            int dz = Math.abs(end.getZ() - start.getZ());

            if (dx >= dz) {
                int minX = Math.min(start.getX(), end.getX());
                int maxX = Math.max(start.getX(), end.getX());
                for (int y = minY; y <= maxY && positions.size() < limit; y++) {
                    for (int x = minX; x <= maxX && positions.size() < limit; x++) {
                        add(positions, new BlockPos(x, y, start.getZ()), limit);
                    }
                }
            } else {
                int minZ = Math.min(start.getZ(), end.getZ());
                int maxZ = Math.max(start.getZ(), end.getZ());
                for (int y = minY; y <= maxY && positions.size() < limit; y++) {
                    for (int z = minZ; z <= maxZ && positions.size() < limit; z++) {
                        add(positions, new BlockPos(start.getX(), y, z), limit);
                    }
                }
            }
        }

        private static void addCuboid(Set<BlockPos> positions, BlockPos start, BlockPos end, boolean hollow, int limit) {
            int minX = Math.min(start.getX(), end.getX());
            int minY = Math.min(start.getY(), end.getY());
            int minZ = Math.min(start.getZ(), end.getZ());
            int maxX = Math.max(start.getX(), end.getX());
            int maxY = Math.max(start.getY(), end.getY());
            int maxZ = Math.max(start.getZ(), end.getZ());

            for (int y = minY; y <= maxY && positions.size() < limit; y++) {
                for (int x = minX; x <= maxX && positions.size() < limit; x++) {
                    for (int z = minZ; z <= maxZ && positions.size() < limit; z++) {
                        boolean boundary = x == minX || x == maxX || y == minY || y == maxY || z == minZ || z == maxZ;
                        if (!hollow || boundary) {
                            add(positions, new BlockPos(x, y, z), limit);
                        }
                    }
                }
            }
        }

        private static void addCircle(Set<BlockPos> positions, BlockPos center, BlockPos edge, int limit) {
            int radius = Math.max(1, (int) Math.round(Math.sqrt(
                    Math.pow(edge.getX() - center.getX(), 2) + Math.pow(edge.getZ() - center.getZ(), 2))));
            int samples = Math.max(24, radius * 16);
            for (int i = 0; i < samples && positions.size() < limit; i++) {
                double angle = Math.PI * 2.0 * i / samples;
                int x = center.getX() + (int) Math.round(Math.cos(angle) * radius);
                int z = center.getZ() + (int) Math.round(Math.sin(angle) * radius);
                add(positions, new BlockPos(x, center.getY(), z), limit);
            }
        }

        private static void addCylinder(Set<BlockPos> positions, BlockPos center, BlockPos end, int limit) {
            int radius = Math.max(1, (int) Math.round(Math.sqrt(
                    Math.pow(end.getX() - center.getX(), 2) + Math.pow(end.getZ() - center.getZ(), 2))));
            int minY = Math.min(center.getY(), end.getY());
            int maxY = Math.max(center.getY(), end.getY());
            int samples = Math.max(24, radius * 16);

            for (int y = minY; y <= maxY && positions.size() < limit; y++) {
                for (int i = 0; i < samples && positions.size() < limit; i++) {
                    double angle = Math.PI * 2.0 * i / samples;
                    int x = center.getX() + (int) Math.round(Math.cos(angle) * radius);
                    int z = center.getZ() + (int) Math.round(Math.sin(angle) * radius);
                    add(positions, new BlockPos(x, y, z), limit);
                }
            }
        }

        private static void addSphere(Set<BlockPos> positions, BlockPos center, BlockPos edge, int limit) {
            int dx = edge.getX() - center.getX();
            int dy = edge.getY() - center.getY();
            int dz = edge.getZ() - center.getZ();
            int radius = Math.max(1, (int) Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz)));
            double inner = Math.max(0, radius - 0.65);
            double outer = radius + 0.65;
            double innerSqr = inner * inner;
            double outerSqr = outer * outer;

            for (int y = -radius; y <= radius && positions.size() < limit; y++) {
                for (int x = -radius; x <= radius && positions.size() < limit; x++) {
                    for (int z = -radius; z <= radius && positions.size() < limit; z++) {
                        double distanceSqr = x * x + y * y + z * z;
                        if (distanceSqr >= innerSqr && distanceSqr <= outerSqr) {
                            add(positions, center.offset(x, y, z), limit);
                        }
                    }
                }
            }
        }

        private static void addStairs(Set<BlockPos> positions, BlockPos start, BlockPos end, int limit) {
            int dx = end.getX() - start.getX();
            int dz = end.getZ() - start.getZ();
            int horizontalSteps = Math.max(Math.abs(dx), Math.abs(dz));
            int verticalDifference = end.getY() - start.getY();
            int steps = Math.max(1, horizontalSteps);

            for (int i = 0; i <= steps && positions.size() < limit; i++) {
                double t = (double) i / steps;
                int x = start.getX() + (int) Math.round(dx * t);
                int y = start.getY() + (int) Math.round(verticalDifference * t);
                int z = start.getZ() + (int) Math.round(dz * t);
                add(positions, new BlockPos(x, y, z), limit);
            }
        }
    }

    private static final class SchematicAssistant {
        private final Map<BlockPos, Long> retryAfter = new HashMap<>();
        private List<PreviewBlock> previewBlocks = List.of();
        private SchematicStats stats = SchematicStats.EMPTY;
        private long nextScanTick;

        private void reset() {
            retryAfter.clear();
            previewBlocks = List.of();
            stats = SchematicStats.EMPTY;
            nextScanTick = 0;
        }

        private void resetTransientState() {
            previewBlocks = List.of();
            stats = SchematicStats.EMPTY;
            nextScanTick = clientTicks;
        }

        private SchematicStats getStats() {
            return stats;
        }

        private List<PreviewBlock> getPreviewBlocks() {
            return previewBlocks;
        }

        private void tick(Minecraft client) {
            if (client.player == null || client.level == null || client.gameMode == null || clientTicks < nextScanTick) {
                return;
            }
            nextScanTick = clientTicks + PLACE_INTERVAL_TICKS;

            LitematicaBridge.initialize();
            if (!LitematicaBridge.isAvailable()) {
                stats = new SchematicStats(false, false, 0, 0, 0, 0, 0, LitematicaBridge.error());
                previewBlocks = List.of();
                return;
            }

            Level schematicWorld = LitematicaBridge.getSchematicWorld();
            if (schematicWorld == null) {
                stats = new SchematicStats(true, false, 0, 0, 0, 0, 0, "");
                previewBlocks = List.of();
                return;
            }

            ItemStack held = client.player.getMainHandItem();
            int scanRadius = Math.max(3, (int) Math.ceil(client.player.blockInteractionRange()) + 2);
            BlockPos center = client.player.blockPosition();
            Vec3 eye = client.player.getEyePosition();

            int missing = 0;
            int matchingHeld = 0;
            int ready = 0;
            int wrongItem = 0;
            int blocked = 0;
            List<PreviewBlock> previews = new ArrayList<>();

            BlockPos bestPos = null;
            BlockHitResult bestHit = null;
            double bestDistance = Double.MAX_VALUE;

            cleanupRetryMap();

            for (int y = center.getY() - scanRadius; y <= center.getY() + scanRadius; y++) {
                for (int x = center.getX() - scanRadius; x <= center.getX() + scanRadius; x++) {
                    for (int z = center.getZ() - scanRadius; z <= center.getZ() + scanRadius; z++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        BlockState desired = schematicWorld.getBlockState(pos);
                        if (desired.isAir()) {
                            continue;
                        }

                        BlockState actual = client.level.getBlockState(pos);
                        if (desired.equals(actual)) {
                            continue;
                        }

                        missing++;
                        if (!actual.canBeReplaced()) {
                            blocked++;
                            addPreview(previews, pos, COLOR_BLOCKED);
                            continue;
                        }

                        ItemStack required = LitematicaBridge.getRequiredItem(desired, schematicWorld, pos);
                        boolean heldMatches = matchesRequiredItem(held, required, desired);
                        if (!heldMatches) {
                            wrongItem++;
                            addPreview(previews, pos, COLOR_WRONG_ITEM);
                            continue;
                        }
                        matchingHeld++;

                        BlockHitResult hit = LitematicaBridge.createClickResult(pos, desired, actual);
                        if (hit == null) {
                            hit = createPlacementHitResult(client.level, pos);
                        }
                        if (hit == null) {
                            addPreview(previews, pos, COLOR_WAITING_SUPPORT);
                            continue;
                        }

                        if (!isWithinPlacementReach(client.player, hit)) {
                            addPreview(previews, pos, COLOR_OUT_OF_RANGE);
                            continue;
                        }

                        ready++;
                        addPreview(previews, pos, COLOR_READY);

                        Long retryTick = retryAfter.get(pos);
                        if (retryTick != null && retryTick > clientTicks) {
                            continue;
                        }

                        double distance = eye.distanceToSqr(hit.getLocation());
                        if (distance < bestDistance) {
                            bestDistance = distance;
                            bestPos = pos;
                            bestHit = hit;
                        }
                    }
                }
            }

            previewBlocks = List.copyOf(previews);
            stats = new SchematicStats(true, true, missing, matchingHeld, ready, wrongItem, blocked, LitematicaBridge.error());

            if (bestPos != null && bestHit != null && !held.isEmpty()) {
                PlaceResult result = tryPlaceHit(client, bestHit);
                if (result == PlaceResult.RETRY) {
                    retryAfter.put(bestPos.immutable(), clientTicks + FAILED_RETRY_DELAY_TICKS);
                } else {
                    retryAfter.remove(bestPos);
                }
            }
        }

        private void cleanupRetryMap() {
            retryAfter.entrySet().removeIf(entry -> entry.getValue() <= clientTicks - 200);
        }

        private static void addPreview(List<PreviewBlock> previews, BlockPos pos, int color) {
            if (previews.size() < MAX_RENDERED_BLOCKS) {
                previews.add(new PreviewBlock(pos.immutable(), color));
            }
        }

        private static boolean matchesRequiredItem(ItemStack held, ItemStack required, BlockState desired) {
            if (held.isEmpty()) {
                return false;
            }
            if (required != null && !required.isEmpty()) {
                return held.getItem() == required.getItem();
            }
            return held.getItem() == desired.getBlock().asItem();
        }
    }

    private static final class LitematicaBridge {
        private static boolean initialized;
        private static boolean available;
        private static String error = "";
        private static Method getSchematicWorld;
        private static Method getMaterialCache;
        private static Method getRequiredBuildItem;
        private static Method getClickPosition;
        private static Object materialCache;

        private LitematicaBridge() {
        }

        private static void initialize() {
            if (initialized) {
                return;
            }
            initialized = true;

            if (!FabricLoader.getInstance().isModLoaded("litematica")) {
                error = "Litematicaが見つかりません";
                return;
            }

            try {
                Class<?> worldHandler = Class.forName("fi.dy.masa.litematica.world.SchematicWorldHandler");
                getSchematicWorld = worldHandler.getMethod("getSchematicWorld");

                Class<?> materialCacheClass = Class.forName("fi.dy.masa.litematica.materials.MaterialCache");
                getMaterialCache = materialCacheClass.getMethod("getInstance");
                getRequiredBuildItem = materialCacheClass.getMethod(
                        "getRequiredBuildItemForState",
                        BlockState.class,
                        Level.class,
                        BlockPos.class);
                materialCache = getMaterialCache.invoke(null);

                Class<?> easyPlaceClass = Class.forName("fi.dy.masa.litematica.util.EasyPlaceUtils");
                getClickPosition = easyPlaceClass.getDeclaredMethod(
                        "getClickPosition",
                        BlockHitResult.class,
                        BlockState.class,
                        BlockState.class);
                getClickPosition.setAccessible(true);

                available = true;
            } catch (Throwable throwable) {
                error = throwable.getClass().getSimpleName();
                available = getSchematicWorld != null;
            }
        }

        private static boolean isAvailable() {
            return available;
        }

        private static String error() {
            return error;
        }

        private static Level getSchematicWorld() {
            if (!available || getSchematicWorld == null) {
                return null;
            }
            try {
                Object world = getSchematicWorld.invoke(null);
                return world instanceof Level level ? level : null;
            } catch (Throwable throwable) {
                error = throwable.getClass().getSimpleName();
                return null;
            }
        }

        private static ItemStack getRequiredItem(BlockState state, Level world, BlockPos pos) {
            if (getRequiredBuildItem == null || materialCache == null) {
                return ItemStack.EMPTY;
            }
            try {
                Object result = getRequiredBuildItem.invoke(materialCache, state, world, pos);
                return result instanceof ItemStack stack ? stack : ItemStack.EMPTY;
            } catch (Throwable throwable) {
                return ItemStack.EMPTY;
            }
        }

        private static BlockHitResult createClickResult(BlockPos pos, BlockState desired, BlockState actual) {
            if (getClickPosition == null) {
                return null;
            }
            try {
                BlockHitResult schematicHit = new BlockHitResult(
                        Vec3.atCenterOf(pos),
                        Direction.UP,
                        pos,
                        false);
                Object result = getClickPosition.invoke(null, schematicHit, desired, actual);
                return result instanceof BlockHitResult hit ? hit : null;
            } catch (Throwable throwable) {
                return null;
            }
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
        if (placementHit == null || !isWithinPlacementReach(player, placementHit)) {
            return PlaceResult.RETRY;
        }

        return tryPlaceHit(client, placementHit);
    }

    private static PlaceResult tryPlaceHit(Minecraft client, BlockHitResult placementHit) {
        LocalPlayer player = client.player;
        if (player == null || client.gameMode == null || !isWithinPlacementReach(player, placementHit)) {
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
