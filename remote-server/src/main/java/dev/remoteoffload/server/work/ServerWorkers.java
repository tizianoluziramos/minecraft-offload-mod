package dev.remoteoffload.server.work;

import dev.remoteoffload.server.ServerConfig;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pool de workers (virtual threads). La concurrencia real se limita con
 * {@link #maxPending} + semáforos por cliente en {@code ClientConnection}.
 */
public final class ServerWorkers implements AutoCloseable {

    private final ExecutorService pool;
    private final int maxPending;
    private final AtomicInteger pending = new AtomicInteger();

    public ServerWorkers(ServerConfig cfg) {
        this.pool = Executors.newVirtualThreadPerTaskExecutor();
        this.maxPending = cfg.maxPending;
    }

    /** Devuelve false si la cola ya está llena (rechazo BUSY, sin bloquear). */
    public boolean submit(Runnable r) {
        if (pending.get() >= maxPending) {
            return false;
        }
        pending.incrementAndGet();
        pool.submit(() -> {
            try {
                r.run();
            } finally {
                pending.decrementAndGet();
            }
        });
        return true;
    }

    public int pendingCount() {
        return pending.get();
    }

    @Override
    public void close() {
        pool.shutdown();
    }
}