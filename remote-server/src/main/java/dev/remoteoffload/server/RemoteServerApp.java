package dev.remoteoffload.server;

import dev.remoteoffload.server.cache.ResultCache;
import dev.remoteoffload.server.net.ServerSocketListener;
import dev.remoteoffload.server.security.AuthManager;
import dev.remoteoffload.server.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Entry point del servidor externo.
 *
 * <pre>
 *   java -jar remote-server.jar [--config path] [--port N] [--token X]
 *                               [--bind HOST] [--workers N] [--noconsole] [--debug]
 * </pre>
 */
public final class RemoteServerApp {

    private static final AtomicBoolean running = new AtomicBoolean(true);

    private RemoteServerApp() {}

    public static void main(String[] args) throws Exception {
        Path configPath = Path.of("remote-server.properties");
        Integer port = null;
        String token = null;
        String bind = null;
        Integer workers = null;
        boolean console = true;
        boolean debug = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--config" -> configPath = Path.of(args[++i]);
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--token" -> token = args[++i];
                case "--bind" -> bind = args[++i];
                case "--workers" -> workers = Integer.parseInt(args[++i]);
                case "--noconsole" -> console = false;
                case "--debug" -> debug = true;
                default -> {
                    System.err.println("unknown argument: " + args[i]);
                    System.err.println("usage: java -jar remote-server.jar [--config path] [--port N] [--token X] [--bind HOST] [--workers N] [--noconsole] [--debug]");
                    System.exit(2);
                }
            }
        }

        ServerConfig cfg = ServerConfig.load(configPath);
        if (port != null) cfg.port = port;
        if (token != null) cfg.token = token;
        if (bind != null) cfg.bindAddress = bind;
        if (workers != null) cfg.workers = workers;
        cfg.validate();

        Log.configure(debug ? Log.Level.DEBUG : Log.Level.INFO, cfg.logFile);
        if (cfg.token.isBlank() && cfg.requireAuth) {
            Log.warn("requireAuth=true but token is EMPTY → auth imposible; se fuerza requireAuth=false");
            cfg.requireAuth = false;
        }
        if (cfg.token.isBlank() && !cfg.requireAuth) {
            Log.warn(">>> SERVER OPEN (sin autenticación). Úsalo solo en LAN/demos.");
        }

        AuthManager auth = new AuthManager(cfg.token, cfg.requireAuth, cfg.allowedClientIds);
        ResultCache cache = new ResultCache(cfg.cacheDir, cfg.cacheMaxMB, 512);
        ServerSocketListener listener = new ServerSocketListener(cfg, auth, cache);

        CountDownLatch shutdown = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            running.set(false);
            try {
                listener.close();
            } catch (Exception ignored) {
            }
            System.out.println("server stopped");
            shutdown.countDown();
        }));

        if (console) {
            Thread.ofPlatform().name("console").start(() -> consoleLoop(listener, cache, auth));
        }

        System.out.println("Remote Offload server up. Clientes: connecta via MKOF :" + cfg.port);
        shutdown.await();
    }

    private static void consoleLoop(ServerSocketListener listener, ResultCache cache, AuthManager auth) {
        System.out.println("console: type 'status', 'clients', 'pending', 'cache', 'list', 'shutdown'");
        try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));) {
            while (running.get()) {
                String line = in.readLine();
                if (line == null) {
                    break;
                }
                switch (line.trim().toLowerCase()) {
                    case "status" -> System.out.println("connections=" + listener.clients().size()
                            + " pending=" + listener.clients().stream().mapToInt(c -> c.pendingTaskCount()).sum());
                    case "clients" -> listener.clients().forEach(c ->
                            System.out.println("  " + c.remote() + " client=" + c.clientId() + " pending=" + c.pendingTaskCount()));
                    case "pending" -> System.out.println("pending tasks: "
                            + listener.clients().stream().mapToInt(c -> c.pendingTaskCount()).sum());
                    case "cache" -> System.out.println("disk cache dir: " + cacheDir());
                    case "shutdown" -> {
                        System.out.println("shutting down...");
                        running.set(false);
                        System.exit(0);
                    }
                    case "list" -> System.out.println("tasks: " + java.util.Arrays.toString(dev.remoteoffload.common.task.TaskType.values()));
                    default -> System.out.println("unknown command");
                }
            }
        } catch (Exception e) {
            Log.debug("console ended: %s", e.getMessage());
        }
    }

    private static String cacheDir() {
        return "cache/ (see remote-server.properties)";
    }
}