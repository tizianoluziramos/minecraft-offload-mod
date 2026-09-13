package dev.remoteoffload.common;

import dev.remoteoffload.common.protocol.LogicalMessage;
import dev.remoteoffload.common.protocol.MalformedPacketException;
import dev.remoteoffload.common.protocol.PacketCodec;
import dev.remoteoffload.common.protocol.PacketType;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CodecTest {

    private static LogicalMessage roundTrip(byte[] frameBytes, int maxFramePayload) throws IOException {
        PacketCodec.MessageReader reader = new PacketCodec.MessageReader(maxFramePayload, 1 << 20);
        InputStream in = new ByteArrayInputStream(frameBytes);
        LogicalMessage out = null;
        while (out == null) {
            out = reader.readMessage(in);
        }
        return out;
    }

    @Test
    void smallMessageNoCompression() throws IOException {
        byte[] payload = "hello-world".getBytes();
        byte[] wire = PacketCodec.frameMessage(PacketType.TASK_SUBMIT.id(), 7L, (byte) 0,
                payload, false, 6, 64 << 10);
        LogicalMessage m = roundTrip(wire, 64 << 10);
        assertEquals(PacketType.TASK_SUBMIT.id(), m.type);
        assertEquals(7L, m.taskId);
        assertArrayEquals(payload, m.payload);
        assertEquals(0, m.flags & 0x02);
    }

    @Test
    void largeFragmentedCompressedRoundTrip() throws IOException {
        Random rnd = new Random(1234);
        byte[] payload = new byte[250_000];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) ("A B C D E ".charAt(rnd.nextInt(9)));
        }
        int maxFrame = 16 << 10;
        byte[] wire = PacketCodec.frameMessage(PacketType.TASK_RESULT.id(), 99L, (byte) 0,
                payload, true, 6, maxFrame);
        LogicalMessage m = roundTrip(wire, maxFrame);
        assertEquals(99L, m.taskId);
        assertEquals(0x02, m.flags & 0x02);
        assertArrayEquals(payload, m.payload);
    }

    @Test
    void corruptedPayloadDetected() throws IOException {
        byte[] payload = "some-data-to-corrupt".getBytes();
        byte[] wire = PacketCodec.frameMessage(PacketType.TASK_RESULT.id(), 1L, (byte) 0,
                payload, false, 6, 64 << 10);
        wire[wire.length / 2] ^= 0x01;
        assertThrows(MalformedPacketException.class,
                () -> roundTrip(wire, 64 << 10));
    }

    @Test
    void incompressiblePayloadRoundTrip() throws IOException {
        Random rnd = new Random(99);
        byte[] payload = new byte[20_000];
        rnd.nextBytes(payload);
        int maxFrame = 4 << 10;
        byte[] wire = PacketCodec.frameMessage(PacketType.TASK_RESULT.id(), 5L, (byte) 0,
                payload, true, 6, maxFrame);
        LogicalMessage m = roundTrip(wire, maxFrame);
        assertArrayEquals(payload, m.payload);
    }

    @Test
    void deflateIsDeterministicGivenLevel() {
        byte[] payload = new byte[5000];
        Arrays.fill(payload, (byte) 'x');
        byte[] a = PacketCodec.deflate(payload, 6);
        byte[] b = PacketCodec.deflate(payload, 6);
        assertArrayEquals(a, b);
    }
}