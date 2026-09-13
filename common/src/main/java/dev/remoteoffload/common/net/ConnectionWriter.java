package dev.remoteoffload.common.net;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Escritor de mensajes ya emmarcados con un único hilo de escritura
 * (atomicidad por mensaje). Compartido por cliente y servidor.
 */
public final class ConnectionWriter implements AutoCloseable {

    private final BlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
    private final OutputStream out;
    private final Thread writer;
    private volatile boolean closed;

    public ConnectionWriter(OutputStream out, String name) {
        this.out = out;
        this.writer = Thread.ofVirtual().name(name + "-writer").start(this::drain);
    }

    private void drain() {
        try {
            while (true) {
                byte[] msg = queue.take();
                if (msg.length == 0) {
                    break;
                }
                out.write(msg);
                out.flush();
            }
        } catch (InterruptedException ignored) {
        } catch (IOException ignored) {
        }
    }

    /** Encola un mensaje (bytes de todas sus tramas). Llama desde cualquier hilo. */
    public void submit(byte[] framedMessage) {
        if (closed) {
            return;
        }
        queue.offer(framedMessage);
    }

    public boolean isAlive() {
        return writer.isAlive();
    }

    @Override
    public void close() {
        closed = true;
        queue.offer(new byte[0]); // centinela de cierre
        try {
            writer.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}