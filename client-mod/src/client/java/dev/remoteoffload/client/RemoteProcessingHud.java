package dev.remoteoffload.client;

import dev.remoteoffload.client.PathState;
import dev.remoteoffload.client.WorldSnapshotter;
import dev.remoteoffload.config.RemoteProcessingConfig;
import dev.remoteoffload.runtime.Diagnostics;
import dev.remoteoffload.runtime.RemoteProcessingEngine;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * Overlay de estado / latencia del offloading.
 */
public final class RemoteProcessingHud {

    private static final Identifier ID = Identifier.fromNamespaceAndPath("remote-offload", "status");
    private static final Identifier CHAT_ID = Identifier.fromNamespaceAndPath("minecraft", "chat");

    private RemoteProcessingHud() {}

    public static void register() {
        HudElementRegistry.attachElementBefore(
                HudElementRegistry.getElementById(CHAT_ID),
                ID,
                RemoteProcessingHud::render);
    }

    private static void render(GuiGraphicsExtractor g, DeltaTracker tracker) {
        Minecraft mc = Minecraft.getInstance();
        RemoteProcessingEngine engine = RemoteProcessingEngine.instance();
        if (engine == null) {
            return;
        }
        RemoteProcessingConfig cfg = engine.config();
        boolean showLatency = cfg.showLatency;
        boolean showStatus = cfg.showStatus;
        if (!showLatency && !showStatus) {
            return;
        }
        int x = 4;
        int y = 4;
        if (showStatus) {
            List<String> lines = Diagnostics.hudLines(engine);
            int dy = 10;
            for (int i = 0; i < lines.size(); i++) {
                int color = i == 0 ? colorFor(engine) : 0xFFAAAAAA;
                g.text(mc.font, lines.get(i), x, y + i * dy, color, true);
            }
            y += lines.size() * dy + 4;
        }
        if (showLatency) {
            g.text(mc.font, "RTT: " + (engine.lastRttUs() / 1000.0) + "ms",
                    x, y, 0xFF55FF55, true);
        }
        renderPath(g, mc);
    }

    private static void renderPath(GuiGraphicsExtractor g, Minecraft mc) {
        if (!PathState.isVisible()) {
            return;
        }
        WorldSnapshotter.Snapshot s = PathState.snapshot();
        int bx = 4;
        int by = 60;
        int scale = Math.max(1, Math.min(4, 96 / s.w()));
        g.fill(bx - 2, by - 2, bx + s.w() * scale + 2, by + s.h() * scale + 2, 0x80000000);
        g.fill(bx, by, bx + s.w() * scale, by + s.h() * scale, 0x40222222);
        int half = Math.min(s.radius(), 24);
        int cx = s.w() / 2;
        int cz = s.h() / 2;
        g.fill(bx + (cx - half) * scale, by + (cz - half) * scale,
                bx + (cx + half + 1) * scale, by + (cz + half + 1) * scale,
                0x40444444);
        if (PathState.goal() != null) {
            int gx = PathState.goal().getX() - s.originX();
            int gz = PathState.goal().getZ() - s.originZ();
            if (gx >= 0 && gx < s.w() && gz >= 0 && gz < s.h()) {
                g.fill(bx + gx * scale, by + gz * scale,
                        bx + (gx + 1) * scale, by + (gz + 1) * scale, 0xFFFF5555);
            }
        }
        g.fill(bx + cx * scale, by + cz * scale, bx + (cx + 1) * scale, by + (cz + 1) * scale, 0xFF55FF55);
    }

    private static int colorFor(RemoteProcessingEngine engine) {
        if (engine.isReady()) {
            return 0xFF55FF55;
        }
        if (engine.lastError() != null && !engine.lastError().isEmpty()) {
            return 0xFFFF5555;
        }
        return 0xFFAAAAAA;
    }
}