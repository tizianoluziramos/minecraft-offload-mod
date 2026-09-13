package dev.remoteoffload.common.protocol;

/**
 * Constantes del protocolo binario MKOF (Minecraft-KOffloader).
 */
public final class ProtocolConstants {

    private ProtocolConstants() {}

    /** "MKOF" */
    public static final long MAGIC = 0x4D4B4F46L;

    public static final int HEADER_SIZE = 24;
    public static final int CRC_SIZE = 4;
    public static final int FRAME_OVERHEAD = HEADER_SIZE + CRC_SIZE;

    public static final int PROTOCOL_VERSION = 1;
    public static final int MIN_PROTOCOL_VERSION = 1;

    /** Límites de paquete (endurecidos; el emisor que los viole será cortado). */
    public static final int MAX_FRAME_PAYLOAD_DEFAULT = 64 * 1024;
    public static final int MAX_FRAME_PAYLOAD_HARD = 1024 * 1024;
    public static final int MAX_PACKET_BYTES_DEFAULT = 1024 * 1024;
    public static final int MAX_PACKET_BYTES_HARD = 16 * 1024 * 1024;

    /** Por debajo de este tamaño no se comprime el payload de un mensaje entero. */
    public static final int COMPRESSION_MIN_PAYLOAD = 512;

    /** Puerta por defecto del server de offload. */
    public static final int DEFAULT_PORT = 19724;

    /** Tamaño del identificador de cliente (bytes). */
    public static final int CLIENT_ID_LENGTH = 16;
    /** Tamaño del nonce y del challenge (bytes). */
    public static final int NONCE_LENGTH = 16;
    public static final int CHALLENGE_LENGTH = 32;

    public static final long FRAGMENT_TIMEOUT_MS = 5_000;
    public static final int MAX_FRAGMENTS = 255;
}