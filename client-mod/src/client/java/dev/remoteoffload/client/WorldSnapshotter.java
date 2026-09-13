package dev.remoteoffload.client;

import dev.remoteoffload.common.serial.Buf;
import dev.remoteoffload.common.task.PathfindExecutor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Construye el snapshot de superficie que se envía al server para
 * {@code PATHFIND}. Debe llamarse en el MAIN thread (curto y barato).
 */
public final class WorldSnapshotter {

    public static final int CELL_AIR = 0;
    public static final int CELL_GROUND = 1;
    public static final int CELL_WATER = 2;
    public static final int CELL_SOLID = 3;

    private WorldSnapshotter() {}

    public record Snapshot(int w, int h, int[] cells, int[] topY,
                           int startIdx, int goalIdx, int originX, int originZ,
                           int radius, byte[] request) {}

    public static Snapshot build(ClientLevel level, BlockPos from, BlockPos to, int radius) {
        int size = Math.min(radius * 2 + 1, 128);
        int half = size / 2;
        int w = size, h = size;
        int originX = from.getX() - half;
        int originZ = from.getZ() - half;

        int[] cells = new int[w * h];
        int[] topY = new int[w * h];
        int maxY = level.getMaxBuildHeight() - 1;
        int minY = level.getMinBuildHeight();

        for (int dz = 0; dz < h; dz++) {
            for (int dx = 0; dx < w; dx++) {
                int bx = originX + dx;
                int bz = originZ + dz;
                int idx = dz * w + dx;
                BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
                pos.set(bx, 0, bz);
                int y = maxY;
                while (y >= minY) {
                    pos.setY(y);
                    BlockState st = level.getBlockState(pos);
                    if (!st.isAir()) {
                        break;
                    }
                    y--;
                }
                if (y < minY) {
                    cells[idx] = CELL_SOLID;
                    topY[idx] = minY;
                    continue;
                }
                BlockState top = level.getBlockState(pos.setY(y));
                if (top.is(Blocks.WATER)) {
                    cells[idx] = CELL_WATER;
                    topY[idx] = y;
                    continue;
                }
                if (top.is(Blocks.LAVA)) {
                    cells[idx] = CELL_SOLID;
                    topY[idx] = y;
                    continue;
                }
                int yAbove = y + 1;
                boolean openAbove = yAbove >= maxY || level.getBlockState(pos.setY(yAbove)).isAir();
                cells[idx] = openAbove ? CELL_GROUND : CELL_SOLID;
                topY[idx] = openAbove ? y : y;
            }
        }

        int fromIdx = clampIndex(from.getX(), from.getZ(), originX, originZ, w, h);
        int toIdx = clampIndex(to.getX(), to.getZ(), originX, originZ, w, h);

        byte[] request = encode(w, h, cells, fromIdx, toIdx);
        return new Snapshot(w, h, cells, topY, fromIdx, toIdx, originX, originZ, half, request);
    }

    private static int clampIndex(int wx, int wz, int originX, int originZ, int w, int h) {
        int dx = Math.max(0, Math.min(w - 1, wx - originX));
        int dz = Math.max(0, Math.min(h - 1, wz - originZ));
        return dz * w + dx;
    }

    public static byte[] encode(int w, int h, int[] cells, int startIdx, int goalIdx) {
        Buf b = Buf.writer();
        b.u8(w).u8(h);
        for (int c : cells) {
            b.u8(c == CELL_GROUND ? PathfindExecutor.GROUND
                    : c == CELL_WATER ? PathfindExecutor.WATER
                    : PathfindExecutor.SOLID);
        }
        b.u16(startIdx).u16(goalIdx)
                .u8(1)          // allowDiag
                .u32(200_000)   // maxNodes
                .i64(30_000_000L); // budgetNs 30 ms
        return b.toByteArray();
    }

    /** Convierte un waypoint (ix, iz) del grid a posición de mundo. */
    public static BlockPos toBlockPos(Snapshot s, int idxX, int idxZ) {
        return new BlockPos(s.originX + idxX, 0, s.originZ + idxZ);
    }
}