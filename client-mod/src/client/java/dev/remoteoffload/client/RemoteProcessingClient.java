package dev.remoteoffload.client;

import net.fabricmc.api.ClientModInitializer;

/** Entrypoint cliente: HUD + comandos (el motor se inicia en el entrypoint main). */
public class RemoteProcessingClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        RemoteProcessingHud.register();
        RemoteProcessingCommands.register();
    }
}