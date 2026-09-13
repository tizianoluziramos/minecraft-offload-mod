package dev.remoteoffload.common.task;

import dev.remoteoffload.common.serial.Buf;

import java.util.Random;

/**
 * Consultas de mundo deterministas por semilla. Resultados SIEMPRE son datos
 * informativos (el cliente decide usarlos); nunca inyectan estado.
 *
 * <p>Request: u8 kind (1=SLIME_CHUNK, 2=SPAWN), u8 dim (0=overworld),
 * u64 seed, i32 x, i32 z.</p>
 *
 * <p>Response: u8 kind, u8 ok, u32 resultCode, i32 outX, i32 outZ, utf note.</p>
 */
public final class WorldQueryExecutor implements TaskHandler {

    public static final int KIND_SLIME = 1;
    public static final int KIND_SPAWN = 2;

    @Override
    public TaskType type() {
        return TaskType.WORLD_QUERY;
    }

    @Override
    public boolean cacheable() {
        return true;
    }

    @Override
    public void validate(byte[] request) throws TaskException {
        Buf r = Buf.reader(request);
        if (r.remaining() < 1 + 1 + 8 + 4 + 4) {
            throw new TaskException(TaskException.ERR_MALFORMED, "world query too short");
        }
        int kind = r.u8();
        if (kind != KIND_SLIME && kind != KIND_SPAWN) {
            throw new TaskException(TaskException.ERR_MALFORMED, "bad kind " + kind);
        }
    }

    @Override
    public byte[] run(byte[] request) throws TaskException {
        validate(request);
        Buf r = Buf.reader(request);
        int kind = r.u8();
        r.u8(); // dim
        long seed = r.i64();
        int x = r.u32();
        int z = r.u32();
        return run(kind, seed, x, z);
    }

    /** Variante con args explícitos (usada por tests / peticiones). */
    public byte[] run(int kind, long seed, int x, int z) throws TaskException {
        Buf w = Buf.writer();
        w.u8(kind);
        switch (kind) {
            case KIND_SLIME -> {
                boolean slime = isSlimeChunk(seed, x, z);
                w.u8(1)                    // ok
                        .u32(slime ? 1 : 0)
                        .u32l(0)           // outX no aplicable
                        .u32l(0)           // outZ
                        .utf(slime ? "slime" : "not-slime");
            }
            case KIND_SPAWN -> {
                long[] spawn = spawnApprox(seed);
                w.u8(1)
                        .u32(1)
                        .u32l((int) spawn[0])
                        .u32l((int) spawn[1])
                        .utf("approx-spawn-candidate");
            }
            default -> throw new TaskException(TaskException.ERR_MALFORMED, "bad kind " + kind);
        }
        return w.toByteArray();
    }

    /**
     * Fórmula canónica de slime chunks (overworld) verificable contra la wiki:
     * es el algoritmo usado por el generador de slime en {@code SlimeSpawner}.
     */
    public static boolean isSlimeChunk(long worldSeed, int chunkX, int chunkZ) {
        Random rnd = new Random(worldSeed
                + (long) chunkX * chunkX * 0x4ac190cL
                + (long) chunkX * 0x5f24fL
                + (long) chunkZ * chunkZ * 0x430583aL
                + (long) chunkZ * 0x1b74f2a07bL);
        return rnd.nextInt(10) == 0;
    }

    /** Zona de spawn aproximada cerca de (0,0) determinista por semilla. */
    public static long[] spawnApprox(long worldSeed) {
        Random rnd = new Random(worldSeed);
        int x = rnd.nextInt(240) - 120;
        int z = rnd.nextInt(240) - 120;
        return new long[]{x, z};
    }
}