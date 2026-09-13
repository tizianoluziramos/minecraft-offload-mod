package dev.remoteoffload.config;

import dev.remoteoffload.common.id.ClientId;
import dev.remoteoffload.common.protocol.ProtocolConstants;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuración persistida del mod (JSON plano en el directorio de config de
 * Fabric). Parser JSON mínimo sin dependencias externas; todos los campos con
 * valores por defecto seguros.
 */
public final class RemoteProcessingConfig {

    public boolean enabled = false;
    public String host = "127.0.0.1";
    public int port = ProtocolConstants.DEFAULT_PORT;
    public String token = "";

    /** ms de timeout de conexión/lectura. */
    public int timeoutMs = 5000;
    /** máx. tareas simultáneas enviadas al server. */
    public int maxConcurrentTasks = 4;
    public boolean compression = true;

    /** "LAN" o "INTERNET" (intervalos de heartbeat distintos). */
    public String mode = "LAN";

    public boolean showLatency;
    public boolean showStatus;
    public boolean autoReconnect = true;

    /** Límite de mensaje lógico (bytes). */
    public int maxPacketBytes = ProtocolConstants.MAX_PACKET_BYTES_DEFAULT;

    /** Identificador de cliente persistente. */
    public String clientId = "";

    public String validateHostWarnings() {
        if (host == null || host.isBlank()) {
            return "host vacío";
        }
        if (port < 1 || port > 65535) {
            return "puerto fuera de rango";
        }
        if (maxConcurrentTasks < 1 || maxConcurrentTasks > 64) {
            return "maxConcurrentTasks fuera de rango";
        }
        return null;
    }

    public String normalize() {
        if (host != null) {
            host = host.trim();
        }
        if (token != null) {
            token = token.trim();
        }
        if (clientId == null || clientId.isBlank()) {
            clientId = ClientId.generate().hex();
        }
        String warn = validateHostWarnings();
        if (warn != null && enabled) {
            enabled = false;
            return "config inválida → off: " + warn;
        }
        return null;
    }

    public long pingIntervalMs() {
        return "INTERNET".equalsIgnoreCase(mode) ? 15_000 : 5_000;
    }

    // ---------------------------------------------------------------- I/O

    public static RemoteProcessingConfig load(Path file) {
        RemoteProcessingConfig cfg;
        if (Files.exists(file)) {
            try {
                String json = Files.readString(file, StandardCharsets.UTF_8);
                cfg = fromJson(json);
            } catch (Exception e) {
                cfg = new RemoteProcessingConfig();
            }
        } else {
            cfg = new RemoteProcessingConfig();
        }
        cfg.normalize();
        return cfg;
    }

    public String save(Path file) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, toJson(this), StandardCharsets.UTF_8);
            return null;
        } catch (Exception e) {
            return "no se pudo guardar la config: " + e.getMessage();
        }
    }

    // ---------------------------------------------------------- json mini

    static String toJson(RemoteProcessingConfig c) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("enabled", String.valueOf(c.enabled));
        m.put("host", c.host);
        m.put("port", String.valueOf(c.port));
        m.put("token", c.token);
        m.put("timeoutMs", String.valueOf(c.timeoutMs));
        m.put("maxConcurrentTasks", String.valueOf(c.maxConcurrentTasks));
        m.put("compression", String.valueOf(c.compression));
        m.put("mode", c.mode);
        m.put("showLatency", String.valueOf(c.showLatency));
        m.put("showStatus", String.valueOf(c.showStatus));
        m.put("autoReconnect", String.valueOf(c.autoReconnect));
        m.put("maxPacketBytes", String.valueOf(c.maxPacketBytes));
        m.put("clientId", c.clientId);
        StringBuilder sb = new StringBuilder("{\n");
        boolean first = true;
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (!first) {
                sb.append(",\n");
            }
            first = false;
            sb.append("  \"").append(esc(e.getKey())).append("\": ");
            if (isNumOrBool(e.getValue())) {
                sb.append(e.getValue());
            } else {
                sb.append('"').append(esc(e.getValue())).append('"');
            }
        }
        return sb.append("\n}\n").toString();
    }

    private static boolean isNumOrBool(String v) {
        return "true".equals(v) || "false".equals(v) || v.matches("-?\\d+");
    }

    private static String esc(String s) {
        StringBuilder sb = new StringBuilder();
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        return sb.toString();
    }

    /** Parsea un objeto JSON plano (solo escalares). Devuelve null si no es JSON. */
    static Map<String, String> parseJson(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        int[] i = {0};
        int n = text.length();
        skipWs(text, i);
        if (i[0] >= n || text.charAt(i[0]) != '{') {
            throw new IllegalArgumentException("no es un objeto json");
        }
        i[0]++;
        while (i[0] < n) {
            skipWs(text, i);
            if (i[0] >= n) {
                break;
            }
            char c = text.charAt(i[0]);
            if (c == '}') {
                i[0]++;
                break;
            }
            if (c != '"') {
                throw new IllegalArgumentException("clave inesperada");
            }
            String key = readString(text, i);
            skipWs(text, i);
            if (i[0] >= n || text.charAt(i[0]) != ':') {
                throw new IllegalArgumentException("falta ':'");
            }
            i[0]++;
            skipWs(text, i);
            String val;
            if (i[0] < n && text.charAt(i[0]) == '"') {
                val = readString(text, i);
            } else {
                int s = i[0];
                while (i[0] < n && text.charAt(i[0]) != ',' && text.charAt(i[0]) != '}') {
                    i[0]++;
                }
                val = text.substring(s, i[0]).trim();
            }
            out.put(key, val);
            skipWs(text, i);
            if (i[0] < n && text.charAt(i[0]) == ',') {
                i[0]++;
            }
        }
        return out;
    }

    private static void skipWs(String s, int[] i) {
        while (i[0] < s.length() && Character.isWhitespace(s.charAt(i[0]))) {
            i[0]++;
        }
    }

    private static String readString(String s, int[] i) {
        StringBuilder sb = new StringBuilder();
        i[0]++; // abrir comilla
        while (i[0] < s.length()) {
            char c = s.charAt(i[0]++);
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\') {
                char e = i[0] < s.length() ? s.charAt(i[0]++) : '\\';
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        if (i[0] + 4 <= s.length()) {
                            sb.append((char) Integer.parseInt(s.substring(i[0], i[0] + 4), 16));
                            i[0] += 4;
                        }
                    }
                    default -> sb.append(e);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    static RemoteProcessingConfig fromJson(String text) {
        RemoteProcessingConfig c = new RemoteProcessingConfig();
        Map<String, String> m = parseJson(text);
        for (Map.Entry<String, String> e : m.entrySet()) {
            String k = e.getKey().toLowerCase();
            String v = e.getValue();
            try {
                switch (k) {
                    case "enabled" -> c.enabled = Boolean.parseBoolean(v);
                    case "host" -> c.host = v;
                    case "port" -> c.port = Integer.parseInt(v);
                    case "token" -> c.token = v;
                    case "timeoutms" -> c.timeoutMs = Integer.parseInt(v);
                    case "maxconcurrenttasks" -> c.maxConcurrentTasks = Integer.parseInt(v);
                    case "compression" -> c.compression = Boolean.parseBoolean(v);
                    case "mode" -> c.mode = v;
                    case "showlatency" -> c.showLatency = Boolean.parseBoolean(v);
                    case "showstatus" -> c.showStatus = Boolean.parseBoolean(v);
                    case "autoreconnect" -> c.autoReconnect = Boolean.parseBoolean(v);
                    case "maxpacketbytes" -> c.maxPacketBytes = Integer.parseInt(v);
                    case "clientid" -> c.clientId = v;
                    default -> {
                    }
                }
            } catch (NumberFormatException ignored) {
                // valor inválido → default
            }
        }
        return c;
    }

    /** Copia editable para la pantalla (evita mutar durante guard cambiando hilos). */
    public RemoteProcessingConfig copy() {
        RemoteProcessingConfig c = new RemoteProcessingConfig();
        c.enabled = enabled;
        c.host = host;
        c.port = port;
        c.token = token;
        c.timeoutMs = timeoutMs;
        c.maxConcurrentTasks = maxConcurrentTasks;
        c.compression = compression;
        c.mode = mode;
        c.showLatency = showLatency;
        c.showStatus = showStatus;
        c.autoReconnect = autoReconnect;
        c.maxPacketBytes = maxPacketBytes;
        c.clientId = clientId;
        return c;
    }
}