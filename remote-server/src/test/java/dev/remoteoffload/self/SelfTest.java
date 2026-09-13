package dev.remoteoffload.self;

import dev.remoteoffload.common.crypto.TokenAuth;
import dev.remoteoffload.common.id.ClientId;
import dev.remoteoffload.common.protocol.LogicalMessage;
import dev.remoteoffload.common.protocol.MalformedPacketException;
import dev.remoteoffload.common.protocol.PacketCodec;
import dev.remoteoffload.common.protocol.PacketType;
import dev.remoteoffload.common.serial.Buf;
import dev.remoteoffload.common.task.LocalExecutors;
import dev.remoteoffload.common.task.CompressExecutor;
import dev.remoteoffload.common.task.PathfindExecutor;
import dev.remoteoffload.common.task.TaskException;
import dev.remoteoffload.common.task.TaskType;
import dev.remoteoffload.common.task.WorldQueryExecutor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * SelfTest ejecutable sin dependencias externas.
 * Valida protocolo/serial/ejecutores/crypto con asserts.
 * Compilar: javac --release 25 -cp common-server-classes SelfTest.java
 * Ejecutar: java -cp common-server-classes;self-classes dev.remoteoffload.self.SelfTest
 */
public class SelfTest {

    private static int ok = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        testFixedWidthRoundTrip();
        testVarIntRoundTrip();
        testUtfRoundTrip();
        testCodecSmall();
        testCodecFragmented();
        testCodecCorruption();
        testCompressRoundTrip();
        testPathfindReachable();
        testSlimeChunk();
        testBenchmarkDeterminism();
        testTokenAuth();
        testClientId();
        System.out.println("\n=== SelfTest: " + ok + " passed, " + fail + " failed ===");
        System.exit(fail == 0 ? 0 : 1);
    }

    private static void testFixedWidthRoundTrip() {
        String name = "fixed width roundtrip";
        try {
            Buf w = Buf.writer();
            w.u8(0xFF).u16(12345).u32(-1).u32l(0xFFFFFFFFL).i64(-999L);
            Buf r = Buf.reader(w.toByteArray());
            check(name, 0xFF == r.u8() && 12345 == r.u16() && -1 == r.u32() && 0xFFFFFFFFL == r.u32l() && -999L == r.i64());
        } catch (Exception e) {
            fail(name, e);
        }
    }

    private static void testVarIntRoundTrip() {
        String name = "varint roundtrip";
        try {
            Buf w = Buf.writer().varInt(300).varInt(-1).varInt(Integer.MIN_VALUE);
            Buf r = Buf.reader(w.toByteArray());
            check(name, 300 == r.varInt() && -1 == r.varInt() && Integer.MIN_VALUE == r.varInt());
        } catch (Exception e) {
            fail(name, e);
        }
    }

    private static void testUtfRoundTrip() {
        String name = "utf roundtrip";
        try {
            String s = "remote-offload ñ √ 漢字";
            Buf w = Buf.writer().utf(s);
            check(name, s.equals(Buf.reader(w.toByteArray()).utf()));
        } catch (Exception e) {
            fail(name, e);
        }
    }

    private static void testCodecSmall() {
        String name = "codec small message";
        try {
            byte[] payload = "ping".getBytes();
            byte[] wire = PacketCodec.frameMessage(PacketType.TASK_SUBMIT.id(), 42L, (byte) 0,
                    payload, false, 6, 1 << 16);
            LogicalMessage m = readOne(wire, 1 << 16);
            check(name, PacketType.TASK_SUBMIT.id() == m.type && 42L == m.taskId
                    && Arrays.equals(payload, m.payload));
        } catch (Exception e) {
            fail(name, e);
        }
    }

    private static void testCodecFragmented() {
        String name = "codec fragmented+compressed";
        try {
            byte[] payload = ("A B C ".repeat(20000)).getBytes(StandardCharsets.UTF_8);
            int maxFrame = 8192;
            byte[] wire = PacketCodec.frameMessage(PacketType.TASK_RESULT.id(), 99L, (byte) 0,
                    payload, true, 6, maxFrame);
            LogicalMessage m = readOne(wire, maxFrame);
            check(name, 99L == m.taskId && Arrays.equals(payload, m.payload));
        } catch (Exception e) {
            fail(name, e);
        }
    }

    private static void testCodecCorruption() {
        String name = "codec corrupted payload";
        try {
            byte[] wire = PacketCodec.frameMessage(PacketType.TASK_RESULT.id(), 1L, (byte) 0,
                    "abcdef".getBytes(), false, 6, 1 << 16);
            wire[wire.length / 2] ^= 0x01;
            readOne(wire, 1 << 16);
            fail(name, "no exception");
        } catch (MalformedPacketException e) {
            check(name, true);
        } catch (Exception e) {
            fail(name, e);
        }
    }

    private static void testCompressRoundTrip() {
        String name = "compress roundtrip";
        try {
            byte[] input = "repeat ".repeat(2000).getBytes(StandardCharsets.UTF_8);
            Buf req = Buf.writer().u8(CompressExecutor.ZLIB).u8(6).bytes(input);
            byte[] resp = LocalExecutors.runLocal(TaskType.COMPRESS, req.toByteArray());
            Buf r = Buf.reader(resp);
            r.u8(); r.u8();
            long inLen = r.u32l();
            long outLen = r.u32l();
            r.u32l();
            byte[] compressed = r.bytes(r.remaining());
            byte[] decompressed = new CompressExecutor().decompress(compressed, 0);
            check(name, inLen == input.length && outLen < inLen && Arrays.equals(input, decompressed));
        } catch (Exception e) {
            fail(name, e);
        }
    }

    private static void testPathfindReachable() {
        String name = "pathfind reachable grid";
        try {
            int W = 6, H = 6;
            Buf req = Buf.writer();
            req.u8(W).u8(H);
            for (int i = 0; i < W * H; i++) req.u8(PathfindExecutor.GROUND);
            req.u16(0).u16(W * H - 1).u8(1).u32(10_000).i64(5_000_000_000L);
            byte[] resp = LocalExecutors.runLocal(TaskType.PATHFIND, req.toByteArray());
            Buf r = Buf.reader(resp);
            int found = r.u8();
            r.u32();
            int pathLen = r.u32();
            check(name, found == 1 && pathLen > 1);
        } catch (Exception e) {
            fail(name, e);
        }
    }

    private static void testSlimeChunk() {
        String name = "slime chunk deterministic";
        try {
            Buf req = Buf.writer().u8(WorldQueryExecutor.KIND_SLIME).u8(0).i64(12345L).u32(7).u32(9);
            byte[] resp = LocalExecutors.runLocal(TaskType.WORLD_QUERY, req.toByteArray());
            Buf r = Buf.reader(resp);
            r.u8(); r.u8();
            int code = r.u32();
            boolean expected = WorldQueryExecutor.isSlimeChunk(12345L, 7, 9);
            check(name, (expected ? 1 : 0) == code);
        } catch (Exception e) {
            fail(name, e);
        }
    }

    private static void testBenchmarkDeterminism() {
        String name = "benchmark determinism";
        try {
            byte[] req = Buf.writer().u32(256).u16(3).u8(0x0F).u8(0).toByteArray();
            long a = Buf.reader(LocalExecutors.runLocal(TaskType.BENCHMARK, req)).u32();
            long b = Buf.reader(LocalExecutors.runLocal(TaskType.BENCHMARK, req)).u32();
            check(name, a == b);
        } catch (Exception e) {
            fail(name, e);
        }
    }

    private static void testTokenAuth() {
        String name = "token auth";
        try {
            byte[] t = "token-12345".getBytes(StandardCharsets.UTF_8);
            byte[] ch = new byte[32];
            byte[] n = new byte[16];
            byte[] a = TokenAuth.computeMac(t, ch, n);
            byte[] b = TokenAuth.computeMac(t, ch, n);
            check(name, TokenAuth.constantTimeEquals(a, b) && !TokenAuth.constantTimeEquals(a, new byte[32]));
        } catch (Exception e) {
            fail(name, e);
        }
    }

    private static void testClientId() {
        String name = "client id hex roundtrip";
        try {
            ClientId id = ClientId.generate();
            check(name, id.equals(ClientId.fromHex(id.hex())) && id.hex().length() == 32);
        } catch (Exception e) {
            fail(name, e);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static LogicalMessage readOne(byte[] wire, int maxFrame) throws IOException {
        PacketCodec.MessageReader reader = new PacketCodec.MessageReader(maxFrame, 1 << 20);
        InputStream in = new ByteArrayInputStream(wire);
        LogicalMessage out = null;
        while (out == null) out = reader.readMessage(in);
        return out;
    }

    private static void check(String name, boolean pass) {
        if (pass) {
            ok++;
            System.out.println("[OK] " + name);
        } else {
            fail(name, "assertion failed");
        }
    }

    private static void fail(String name, Throwable t) {
        fail++;
        System.out.println("[FAIL] " + name + " — " + t.getClass().getSimpleName() + ": " + t.getMessage());
    }

    private static void fail(String name, String msg) {
        fail++;
        System.out.println("[FAIL] " + name + " — " + msg);
    }
}