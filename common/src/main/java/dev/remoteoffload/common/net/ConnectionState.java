package dev.remoteoffload.common.net;

/** Estados de la sesión del agente del cliente. */
public enum ConnectionState {
    DISCONNECTED,
    CONNECTING,
    HANDSHAKE,
    AUTH,
    READY,
    BACKOFF
}