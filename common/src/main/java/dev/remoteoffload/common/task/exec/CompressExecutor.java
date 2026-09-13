package dev.remoteoffload.common.task;

import dev.remoteoffload.common.serial.Buf;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.CRC32C;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Tarea de compresión/descompresión. Pura (función determinista) → sin riesgo
 * de desincronización y cacheable por hash de contenido.
 *
 * <p>Request: u8 algo (0=zlib, 1=gzip, 2=rawDeflate), u8 level, bytes…</p>
 * <p>Response: u8 algo, u8 level, u64l lenIn, u64l lenOut, u32l crc32c(in), bytes…</p>
 */
public final class CompressExecutor implements TaskHandler {

    public static final int MAX_BLOB = 8 * 1024 * 1024;

    public static final int ZLIB = 0;
    public static final int GZIP = 1;
    public static final int RAW = 2;

    @Override
    public TaskType type() {
        return TaskType.COMPRESS;
    }

    @Override
    public boolean cacheable() {
        return true;
    }

    @Override
    public void validate(byte[] request) throws TaskException {
        if (request.length < 2) {
            throw new TaskException(TaskException.ERR_MALFORMED, "compress request too short");
        }
        Buf r = Buf.reader(request);
        int algo = r.u8();
        int level = r.u8();
        if (algo < ZLIB || algo > RAW) {
            throw new TaskException(TaskException.ERR_MALFORMED, "bad algorithm " + algo);
        }
        if (level < 1 || level > 9) {
            throw new TaskException(TaskException.ERR_MALFORMED, "bad level " + level);
        }
        if (r.remaining() > MAX_BLOB) {
            throw new TaskException(TaskException.ERR_TOO_LARGE, "blob too large");
        }
    }

    @Override
    public byte[] run(byte[] request) throws TaskException {
        validate(request);
        Buf r = Buf.reader(request);
        int algo = r.u8();
        int level = r.u8();
        byte[] input = r.bytes(r.remaining());

        byte[] compressed = compress(input, algo, level);

        long crc;
        CRC32C c = new CRC32C();
        c.update(input);
        crc = c.getValue();

        Buf w = Buf.writer();
        w.u8(algo).u8(level)
                .u32l(input.length)
                .u32l(compressed.length)
                .u32l(crc & 0xFFFFFFFFL)
                .bytes(compressed);
        return w.toByteArray();
    }

    public static byte[] compress(byte[] data, int algo, int level) throws TaskException {
        try {
            if (algo == GZIP) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream(data.length / 2);
                try (GZIPOutputStream gz = new GZIPOutputStream(bos, data.length)) {
                    gz.write(data);
                }
                return bos.toByteArray();
            }
            Deflater d = new Deflater(level, algo == RAW);
            d.setInput(data);
            d.finish();
            ByteArrayOutputStream bos = new ByteArrayOutputStream(data.length / 2);
            byte[] buf = new byte[8192];
            while (!d.finished()) {
                int n = d.deflate(buf);
                bos.write(buf, 0, n);
            }
            d.end();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new TaskException(TaskException.ERR_INTERNAL, "compress failed", e);
        }
    }

    public byte[] decompress(byte[] data, int algo) throws TaskException {
        try {
            if (algo == GZIP) {
                try (InflaterInputStream in = new InflaterInputStream(
                        new java.io.ByteArrayInputStream(data),
                        new Inflater(true))) {
                    return in.readAllBytes();
                }
            }
            Inflater inf = new Inflater(algo == RAW);
            inf.setInput(data);
            ByteArrayOutputStream bos = new ByteArrayOutputStream(data.length * 3);
            byte[] buf = new byte[8192];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0) {
                    break;
                }
                bos.write(buf, 0, n);
            }
            inf.end();
            return bos.toByteArray();
        } catch (IOException | java.util.zip.DataFormatException e) {
            throw new TaskException(TaskException.ERR_INTERNAL, "decompress failed", e);
        }
    }
}