package dev.remoteoffload.client;

import dev.remoteoffload.client.WorldSnapshotter;
import net.minecraft.core.BlockPos;

/** Última ruta calculada (para overlay del HUD). Hilo seguro. */
public final class PathState {

    private static volatile WorldSnapshotter.Snapshot lastSnapshot;
    private static volatile boolean visible;
    private static volatile BlockPos goalWorld;

    private PathState() {}

    public static void update(WorldSnapshotter.Snapshot snapshot, BlockPos goal) {
        lastSnapshot = snapshot;
        goalWorld = goal;
        visible = true;
    }

    public static void clear() {
        visible = false;
    }

    public static boolean isVisible() {
        return visible && lastSnapshot != null;
    }

    public static WorldSnapshotter.Snapshot snapshot() {
        return lastSnapshot;
    }

    public static BlockPos goal() {
        return goalWorld;
    }
}