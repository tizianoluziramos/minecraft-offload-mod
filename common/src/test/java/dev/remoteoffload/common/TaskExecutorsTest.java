package dev.remoteoffload.common;

import dev.remoteoffload.common.crypto.TokenAuth;
import dev.remoteoffload.common.id.ClientId;
import dev.remoteoffload.common.serial.Buf;
import dev.remoteoffload.common.task.LocalExecutors;
import dev.remoteoffload.common.task.TaskException;
import dev.remoteoffload.common.task.TaskType;
import dev.remoteoffload.common.task.BenchmarkExecutor;
import dev.remoteoffload.common.task.CompressExecutor;
import dev.remoteoffload.common.task.PathfindExecutor;
import dev.remoteoffload.common.task.WorldQueryExecutor;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32C;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskExecutorsTest {

    @Test
    void compressRoundTrip() throws TaskException {
        String text = "repeat repeat repeat ".repeat(1000);
        byte[] input = text.getBytes(StandardCharsets.UTF_8);
        Buf req = Buf.writer();
        req.u8(CompressExecutor.ZLIB).u8(6).bytes(input);
        byte[] resp = LocalExecutors.runLocal(TaskType.COMPRESS, req.toByteArray());

        Buf r = Buf.reader(resp);
        int algo = r.u8();
        int level = r.u8();
        long inLen = r.u32l();
        long outLen = r.u32l();
        long crc = r.u32l();
        byte[] compressed = r.bytes(r.remaining());

        assertEquals(CompressExecutor.ZLIB, algo);
        assertEquals(input.length, inLen);
        assertTrue(outLen < inLen, "compression should reduce size");

        CompressExecutor ex = new CompressExecutor();
        byte[] decompressed = ex.decompress(compressed, algo);
        assertArrayEquals(input, decompressed);

        CRC32C c = new CRC32C();
        c.update(input);
        assertEquals(c.getValue(), crc);
    }

    @Test
    void compressMalformedAlgo() {
        Buf req = Buf.writer().u8(5).u8(1).bytes(new byte[]{1});
        assertThrows(TaskException.class,
                () -> LocalExecutors.runLocal(TaskType.COMPRESS, req.toByteArray()));
    }

    @Test
    void pathfindSimpleGrid() throws TaskException {
        int W = 8, H = 8;
        byte[] cells = new byte[W * H];
        java.util.Arrays.fill(cells, (byte) PathfindExecutor.GROUND);
        int start = 0, goal = W * H - 1;
        Buf req = Buf.writer();
        req.u8(W).u8(H);
        for (byte c : cells) req.u8(c);
        req.u16(start).u16(goal).u8(1).u32(100_000).i64(10_000_000_000L);

        byte[] resp = LocalExecutors.runLocal(TaskType.PATHFIND, req.toByteArray());
        Buf r = Buf.reader(resp);
        int found = r.u8();
        int nodes = r.u32();
        int pathLen = r.u32();
        int cost = r.u32();
        r.u32l(); // time
        assertEquals(1, found);
        assertTrue(pathLen > 1);
        assertTrue(cost > 0);
    }

    @Test
    void pathfindUnreachable() throws TaskException {
        int W = 3, H = 3;
        byte[] cells = new byte[W * H];
        java.util.Arrays.fill(cells, (byte) PathfindExecutor.GROUND);
        cells[4] = (byte) PathfindExecutor.SOLID;
        cells[0] = (byte) PathfindExecutor.SOLID;
        cells[8] = (byte) PathfindExecutor.SOLID;
        Buf req = Buf.writer();
        req.u8(W).u8(H);
        for (byte c : cells) req.u8(c);
        req.u16(0).u16(8).u8(0).u32(1_000).i64(1_000_000L);
        byte[] resp = LocalExecutors.runLocal(TaskType.PATHFIND, req.toByteArray());
        int found = Buf.reader(resp).u8();
        assertEquals(0, found);
    }

    @Test
    void worldQuerySlime() throws TaskException {
        Buf req = Buf.writer();
        req.u8(WorldQueryExecutor.KIND_SLIME).u8(0).i64(42L).u32(0).u32(0);
        byte[] resp = LocalExecutors.runLocal(TaskType.WORLD_QUERY, req.toByteArray());
        Buf r = Buf.reader(resp);
        assertEquals(WorldQueryExecutor.KIND_SLIME, r.u8());
        assertEquals(1, r.u8()); // ok
        int code = r.u32();
        r.u32l(); r.u32l();
        String note = r.utf();
        boolean expected = WorldQueryExecutor.isSlimeChunk(42L, 0, 0);
        assertEquals(expected ? 1 : 0, code);
        assertEquals(expected, note.contains("slime"));
    }

    @Test
    void benchmarkDeterminism() throws TaskException {
        byte[] req1 = Buf.writer().u32(1024).u16(10).u8(0x0F).u8(7).toByteArray();
        byte[] req2 = Buf.writer().u32(1024).u16(10).u8(0x0F).u8(7).toByteArray();
        byte[] resp1 = LocalExecutors.runLocal(TaskType.BENCHMARK, req1);
        byte[] resp2 = LocalExecutors.runLocal(TaskType.BENCHMARK, req2);
        long checksum1 = Buf.reader(resp1).u32();
        long checksum2 = Buf.reader(resp2).u32();
        assertEquals(checksum1, checksum2, "benchmark must be deterministic");
    }

    @Test
    void tokenAuthMacSymmetry() {
        byte[] token = "secret-token-12345".getBytes(StandardCharsets.UTF_8);
        byte[] challenge = new byte[32];
        byte[] nonce = new byte[16];
        byte[] mac1 = TokenAuth.computeMac(token, challenge, nonce);
        byte[] mac2 = TokenAuth.computeMac(token, challenge, nonce);
        assertArrayEquals(mac1, mac2);
        assertTrue(TokenAuth.constantTimeEquals(mac1, mac2));
        assertFalse(TokenAuth.constantTimeEquals(mac1, new byte[32]));
    }

    @Test
    void clientIdHexRoundTrip() {
        ClientId id = ClientId.generate();
        ClientId id2 = ClientId.fromHex(id.hex());
        assertEquals(id, id2);
        assertEquals(32, id.hex().length());
    }
}