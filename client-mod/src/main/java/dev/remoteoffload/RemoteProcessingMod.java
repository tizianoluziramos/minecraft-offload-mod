package dev.remoteoffload;

import dev.remoteoffload.config.RemoteProcessingConfig;
import dev.remoteoffload.config.RemoteProcessingStore;
import dev.remoteoffload.runtime.RemoteProcessingEngine;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point main del mod (se ejecuta en el cliente).
 */
public class RemoteProcessingMod implements ModInitializer {

    public static final String MOD_ID = "remote-offload";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        RemoteProcessingConfig cfg = RemoteProcessingStore.load();
        String warn = cfg.normalize();
        if (warn != null) {
            LOGGER.warn(warn);
        }
        RemoteProcessingEngine.init(cfg);
        LOGGER.info("Remote Offload engine initialised (enabled={})", cfg.enabled);
        Runtime.getRuntime().addShutdownHook(new Thread(RemoteProcessingEngine::shutdownSafe));
    }
}