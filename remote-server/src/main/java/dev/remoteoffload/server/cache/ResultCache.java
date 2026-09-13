package dev.remoteoffload.server.cache;

import dev.remoteoffload.common.task.TaskType;
import dev.remoteoffload.common.util.Hash;
import dev.remoteoffload.server.util.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Caché de resultados con LRU en memoria y respaldo en disco.
 * La clave incluye el tipo de tarea + hash del request canónico → el resultado
 * es siempre una función determinista de la petición.
 */
public final class ResultCache implements AutoCloseable {

    private final Path dir;
    private final long maxDiskBytes;
    private final int maxMemoryEntries;
    private final LinkedHashMap<String, byte[]> memory;
    private final BlockingQueue<Path> eviction = new LinkedBlockingQueue<>();

    public ResultCache(Path dir, long maxDiskMB, int maxMemoryEntries) throws IOException {
        this.dir = dir;
        this.maxDiskBytes = maxDiskMB * 1024 * 1024;
        this.maxMemoryEntries = maxMemoryEntries;
        Files.createDirectories(dir);
        this.memory = new LinkedHashMap<>(128, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                return size() > ResultCache.this.maxMemoryEntries;
            }
        };
        Thread.ofPlatform().name("cache-evictor").daemon(true).start(this::evictLoop);
    }

    public static String key(TaskType type, byte[] request) {
        return type.id() + "-" + Hash.hex(Hash.sha256(request));
    }

    public synchronized byte[] get(String key) {
        byte[] hit = memory.get(key);
        if (hit != null) {
            return hit;
        }
        Path f = dir.resolve(key);
        if (Files.exists(f)) {
            try {
                byte[] data = Files.readAllBytes(f);
                memory.put(key, data);
                return data;
            } catch (IOException e) {
                Log.warn("cache read failed %s", key);
            }
        }
        return null;
    }

    public void put(String key, byte[] data) {
        synchronized (this) {
            memory.put(key, data);
        }
        eviction.offer(dir.resolve(key));
    }

    private void evictLoop() {
        while (true) {
            try {
                Path f = eviction.take();
                Files.write(f, dataOf(f));
                trimDisk();
            } catch (InterruptedException e) {
                return;
            } catch (IOException e) {
                Log.debug("cache write failed: %s", e.getMessage());
            }
        }
    }

    private byte[] dataOf(Path f) {
        String key = f.getFileName().toString();
        synchronized (this) {
            return memory.get(key);
        }
    }

    private void trimDisk() throws IOException {
        if (!Files.isDirectory(dir) || Files.walk(dir).count() == 0) {
            return;
        }
        java.util.List<Path> files;
        try (var stream = Files.walk(dir)) {
            files = stream.filter(Files::isRegularFile).sorted(java.util.Comparator.comparingLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0;
                }
            })).toList();
        }
        long total = files.stream().mapToLong(p -> {
            try {
                return Files.size(p);
            } catch (IOException e) {
                return 0;
            }
        }).sum();
        Iterator<Path> it = files.iterator();
        while (total > maxDiskBytes && it.hasNext()) {
            Path p = it.next();
            long sz;
            try {
                sz = Files.size(p);
                Files.deleteIfExists(p);
            } catch (IOException e) {
                sz = 0;
            }
            total -= sz;
        }
    }

    @Override
    public void close() {
        eviction.offer(Path.of("__shutdown__"));
    }
}