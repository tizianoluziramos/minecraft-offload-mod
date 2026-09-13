package dev.remoteoffload.server.security;

import dev.remoteoffload.common.crypto.TokenAuth;
import dev.remoteoffload.common.id.ClientId;
import dev.remoteoffload.server.util.Log;

import java.util.Set;

/**
 * Gestión de autenticación por token + lista blanca de clientes.
 * Los desafíos se generan por conexión y se almacenan en la propia sesión.
 */
public final class AuthManager {

    private final byte[] token;
    private final boolean requireAuth;
    private final Set<String> allowedClientIds;

    public AuthManager(String token, boolean requireAuth, Set<String> allowedClientIds) {
        this.token = token == null ? new byte[0] : token.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        this.requireAuth = requireAuth;
        this.allowedClientIds = allowedClientIds;
    }

    public boolean requireAuth() {
        return requireAuth;
    }

    public String restrictionInfo() {
        if (!requireAuth) {
            return "open (no auth)";
        }
        return "auth required" + (allowedClientIds.isEmpty() ? "" : ", whitelist enabled");
    }

    /**
     * Verifica la respuesta AUTH: mac == HMAC(token, challenge‖nonce) y,
     * si hay lista blanca, clientId presente.
     */
    public boolean verify(ClientId clientId, byte[] challenge, byte[] nonce, byte[] mac) {
        if (!TokenAuth.constantTimeEquals(mac,
                TokenAuth.computeMac(token, challenge, nonce))) {
            Log.warn("AUTH_FAIL client=%s", clientId);
            return false;
        }
        if (!allowedClientIds.isEmpty() && !allowedClientIds.contains(clientId.hex())) {
            Log.warn("AUTH_FAIL whitelist client=%s", clientId);
            return false;
        }
        Log.info("AUTH_OK client=%s", clientId);
        return true;
    }
}