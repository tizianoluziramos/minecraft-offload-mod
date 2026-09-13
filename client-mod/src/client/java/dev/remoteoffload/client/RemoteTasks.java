package dev.remoteoffload.client;

import dev.remoteoffload.common.serial.Buf;
import dev.remoteoffload.common.task.TaskType;
import dev.remoteoffload.runtime.RemoteProcessingEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Helpers tipados sobre el motor (misma forma de datos en local y remoto).
 */
public final class RemoteTasks {

    private RemoteTasks() {}

    public record PathResult(boolean found, int nodesExpanded, int cost, long timeUs,
                             List<int[]> pathGrid) {}

    public record CompressResult(int algo, int level, long inLen, long outLen, long crc,
                                 byte[] compressed) {}

    public record BenchResult(long checksum, long timeUs) {}

    public record SlimeResult(boolean isSlimeChunk, String note) {}

    public static CompletableFuture<PathResult> pathfind(RemoteProcessingEngine engine, byte[] request) {
        return engine.submit(TaskType.PATHFIND, request).thenApply(RemoteTasks::parsePath);
    }

    public static CompletableFuture<CompressResult> compress(RemoteProcessingEngine engine,
                                                             byte[] data, int algo, int level) {
        Buf w = Buf.writer();
        w.u8(algo).u8(level).bytes(data);
        return engine.submit(TaskType.COMPRESS, w.toByteArray()).thenApply(RemoteTasks::parseCompress);
    }

    public static CompletableFuture<SlimeResult> slimeChunk(RemoteProcessingEngine engine,
                                                            long seed, int chunkX, int chunkZ) {
        Buf w = Buf.writer();
        w.u8(WorldQueryExecutor.KIND_SLIME).u8(0).i64(seed).u32(chunkX).u32(chunkZ);
        return engine.submit(TaskType.WORLD_QUERY, w.toByteArray()).thenApply(RemoteTasks::parseSlime);
    }

    public static CompletableFuture<BenchResult> bench(RemoteProcessingEngine engine,
                                                       int size, int iters, int mask, int seed) {
        Buf w = Buf.writer();
        w.u32(size).u16(iters).u8(mask).u8(seed);
        return engine.submit(TaskType.BENCHMARK, w.toByteArray()).thenApply(RemoteTasks::parseBench);
    }

    // ------------------------------------------------------------- parsing

    public static PathResult parsePath(byte[] resp) {
        Buf r = Buf.reader(resp);
        int found = r.u8();
        int nodes = r.u32();
        int pathLen = r.u32();
        int cost = r.u32();
        long timeUs = r.u32l();
        List<int[]> path = new ArrayList<>(pathLen);
        for (int i = 0; i < pathLen; i++) {
            path.add(new int[]{r.u8(), r.u8()});
        }
        return new PathResult(found != 0, nodes, cost, timeUs, path);
    }

    public static CompressResult parseCompress(byte[] resp) {
        Buf r = Buf.reader(resp);
        int algo = r.u8();
        int level = r.u8();
        long in = r.u32l();
        long out = r.u32l();
        long crc = r.u32l();
        byte[] bytes = r.bytes(r.remaining());
        return new CompressResult(algo, level, in, out, crc, bytes);
    }

    public static SlimeResult parseSlime(byte[] resp) {
        Buf r = Buf.reader(resp);
        r.u8(); // kind
        r.u8(); // ok
        int code = r.u32();
        r.u32l(); // outX
        r.u32l(); // outZ
        String note = r.hasRemaining() ? r.utf() : "";
        return new SlimeResult(code == 1, note);
    }

    public static BenchResult parseBench(byte[] resp) {
        Buf r = Buf.reader(resp);
        long checksum = Integer.toUnsignedLong(r.u32());
        long timeUs = r.u32l();
        r.u32l(); // fibUs
        r.u32l(); // primesUs
        r.u32l(); // shaUs
        r.u32l(); // sortUs
        return new BenchResult(checksum, timeUs);
    }
}