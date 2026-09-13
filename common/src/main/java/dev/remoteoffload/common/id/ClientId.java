package dev.remoteoffload.common.id;

import dev.remoteoffload.common.protocol.ProtocolConstants;
import dev.remoteoffload.common.serial.Buf;
import dev.remoteoffload.common.util.Hash;

import java.security.SecureRandom;
import java.util.Objects;

/**
 * Identificador único de cliente: 16 bytes aleatorios persistidos en la
 * configuración del mod. Se usa para listas blanca y rate-limiting.
 */
public final class ClientId {

    private final byte[] bytes;

    public ClientId(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length != ProtocolConstants.CLIENT_ID_LENGTH) {
            throw new IllegalArgumentException("client id must be 16 bytes");
        }
        this.bytes = bytes.clone();
    }

    public static ClientId generate() {
        byte[] b = new byte[ProtocolConstants.CLIENT_ID_LENGTH];
        new SecureRandom().nextBytes(b);
        return new ClientId(b);
    }

    public byte[] bytes() {
        return bytes.clone();
    }

    public String hex() {
        return Hash.hex(bytes);
    }

    public static ClientId fromHex(String s) {
        byte[] b = Hash.fromHex(s);
        return new ClientId(b);
    }

    public void write(Buf w) {
        w.bytes(bytes);
    }

    public static ClientId read(Buf r) {
        return new ClientId(r.bytes(ProtocolConstants.CLIENT_ID_LENGTH));
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof ClientId c && java.util.Arrays.equals(bytes, c.bytes));
    }

    @Override
    public int hashCode() {
        return java.util.Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return hex();
    }
}