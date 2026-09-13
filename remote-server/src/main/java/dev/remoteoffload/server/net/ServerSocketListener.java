package dev.remoteoffload.server.net;

import dev.remoteoffload.server.ServerConfig;
import dev.remoteoffload.server.cache.ResultCache;
import dev.remoteoffload.server.security.AuthManager;
import dev.remoteoffload.server.security.RateLimiter;
import dev.remoteoffload.server.util.Log;
import dev.remoteoffload.server.work.ServerWorkers;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Listener TCP (accept loop en virtual thread).
 */
public final class ServerSocketListener implements AutoCloseable {

    private final ServerSocket serverSocket;
    private final ServerConfig cfg;
    private final AuthManager auth;
    private final RateLimiter limiter = new RateLimiter(120, 240);
    private final RateLimiter preAuth = new RateLimiter(10, 20);
    private final ResultCache cache;
    private final ServerWorkers workers;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final List<ClientConnection> clients = new CopyOnWriteArrayList<>();

    public ServerSocketListener(ServerConfig cfg, AuthManager auth, ResultCache cache) throws IOException {
        this.cfg = cfg;
        this.auth = auth;
        this.cache = cache;
        this.workers = new ServerWorkers(cfg);
        this.serverSocket = new ServerSocket();
        this.serverSocket.setReuseAddress(true);
        this.serverSocket.bind(new InetSocketAddress(cfg.bindAddress, cfg.port));
        Log.info("listening on %s:%d (%s)", cfg.bindAddress, cfg.port, auth.restrictionInfo());
        Thread.ofVirtual().name("accept-loop").start(this::acceptLoop);
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                String ip = ((InetSocketAddress) socket.getRemoteSocketAddress()).getAddress().getHostAddress();
                if (!preAuth.allow("pre-" + ip)) {
                    Log.warn("dropping %s: pre-auth rate limit", ip);
                    socket.close();
                    continue;
                }
                ClientConnection conn = new ClientConnection(socket, cfg, auth, limiter, cache, workers, this);
                clients.add(conn);
                Thread.ofVirtual().name("client-" + ip).start(conn::run);
            } catch (IOException e) {
                if (running.get()) {
                    Log.error(e, "accept failed: %s", e.getMessage());
                }
            }
        }
    }

    public void onDisconnect(ClientConnection conn) {
        clients.remove(conn);
    }

    public List<ClientConnection> clients() {
        return List.copyOf(clients);
    }

    public void shutdownNow() {
        running.set(false);
        for (ClientConnection c : clients) {
            c.closePeer();
        }
    }

    @Override
    public void close() throws IOException {
        running.set(false);
        for (ClientConnection c : clients) {
            c.closePeer();
        }
        try {
            serverSocket.close();
        } catch (IOException e) {
            Log.debug("server socket close: %s", e.getMessage());
        }
        workers.close();
    }
}