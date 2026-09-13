package dev.remoteoffload.config;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

/** Ubicación del archivo de configuración (dir config de Fabric). */
public final class RemoteProcessingStore {

    private RemoteProcessingStore() {}

    public static Path configFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("remote-offload.json");
    }

    public static RemoteProcessingConfig load() {
        return RemoteProcessingConfig.load(configFile());
    }

    public static String save(RemoteProcessingConfig cfg) {
        return cfg.save(configFile());
    }
}