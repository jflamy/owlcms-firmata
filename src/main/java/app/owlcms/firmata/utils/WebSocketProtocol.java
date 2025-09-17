package app.owlcms.firmata.utils;

/**
 * Utility to pick websocket protocol based on a port string or number.
 *
 * Rules:
 * - If the port is exactly "443" or ends with "443" => "wss"
 * - Else if the port starts with '8' => "ws"
 * - Otherwise => null
 */
public final class WebSocketProtocol {
    private WebSocketProtocol() {
        // utility
    }

    public static String selectProtocol(String port) {
        if (port == null) return null;
        String p = port.trim();
        if (p.endsWith("443")) {
            return "wss";
        }

        // Explicitly treat standard MQTT ports as plain MQTT (TCP), not websockets
        // Common MQTT ports: 1883 (plain), 8883 (secure)
        if ("1883".equals(p) || "8883".equals(p)) {
            return "mqtt";
        }

        // Ports that commonly indicate a websocket listener often start with '8' (e.g. 8080, 8000)
        if (p.startsWith("8")) {
            return "ws";
        }

        // Default to plain MQTT for any other ports
        return "mqtt";
    }

    public static String selectProtocol(int port) {
        return selectProtocol(String.valueOf(port));
    }

    /**
     * Build a URL for the given server and port using the protocol returned by
     * {@link #selectProtocol(String)}. The protocol returned by
     * {@link #selectProtocol(String)} is never null (it will be "mqtt" if no
     * websocket protocol applies). If the server is null/empty this method
     * returns null.
     * <p>
     * For "ws" and "wss" protocols this method appends "/mqtt" to the
     * generated URL (if not already present). For the "mqtt" protocol the
     * returned URL does not include a trailing "/mqtt". Examples:
     * wss://example.com:443/mqtt, ws://localhost:8080/mqtt, mqtt://broker:1883
     */
    public static String buildUrl(String server, String port) {
        String proto = selectProtocol(port);
        if (server == null) return null;
        String s = server.trim();
        if (s.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        sb.append(proto).append("://").append(s);
        if (port != null && !port.trim().isEmpty()) {
            sb.append(':').append(port.trim());
        }
        // Only append "/mqtt" for websocket protocols
        if (("ws".equals(proto) || "wss".equals(proto)) && !sb.toString().endsWith("/mqtt")) {
            sb.append("/mqtt");
        }
        return sb.toString();
    }
}
