package cdpai.netrec;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.regex.*;

public final class J {
    public static final ObjectMapper OM = new ObjectMapper();
    public static final String
        VERSION = "0.2",
        DB_NAME = "netrec",
        ROOT_USER = "root",
        ROOT_PW = "netrec_local";
    public static final int CTL_PORT = 2477;
    /**
     * The hub's HTTP port is a RANGE, not a port: other ArcadeDB apps on this machine also default to 2480, and
     * that endpoint is only the SQL/Studio escape hatch -- losing it must never stop the daemon. The port actually
     * bound is reported by /ping, `netrec status` and `netrec config`.
     */
    public static final String HUB_PORT_DEFAULT = "2480-2489";
    static final Pattern SINCE = Pattern.compile("(\\d+)\\s*([smhd])");
    static final Pattern PORTS = Pattern.compile("(\\d{1,5})(?:\\s*-\\s*(\\d{1,5}))?");

    private J() {}

    public static Path home() { return Path.of(System.getProperty("user.home"), "cdpai", "netrec"); }
    public static Path dbDir() { return home().resolve("databases"); }
    public static Path daemonLog() { return home().resolve("daemon.log"); }

    public static ObjectNode obj() { return OM.createObjectNode(); }

    public static String str(JsonNode n, String field) { return n.hasNonNull(field) ? n.get(field).asText() : null; }
    public static int i(JsonNode n, String field, int def) { return n.hasNonNull(field) ? n.get(field).asInt() : def; }
    public static long l(JsonNode n, String field, long def) { return n.hasNonNull(field) ? n.get(field).asLong() : def; }
    public static boolean b(JsonNode n, String field, boolean def) { return n.hasNonNull(field) ? n.get(field).asBoolean() : def; }

    static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static String iso(long ms) {
        return ms == 0 ? "-" : LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault()).format(CLOCK);
    }

    public static String duration(long ms) {
        var s = ms / 1000;
        return s < 60 ? s + "s" : s < 3600 ? (s / 60) + "m" : (s / 3600) + "h" + (s % 3600 / 60) + "m";
    }

    public static String pretty(Object o) {
        try { return OM.writerWithDefaultPrettyPrinter().writeValueAsString(o); }
        catch (Exception e) { return String.valueOf(o); }
    }

    /** "2480" or "2480-2489" -> {first, last}. Validates so a bad spec fails at the CLI, not inside ArcadeDB. */
    public static int[] portRange(String spec) {
        var m = PORTS.matcher(spec == null ? "" : spec.trim());
        if (!m.matches()) throw new IllegalArgumentException("bad port spec (use 2480 or 2480-2489): " + spec);
        var first = Integer.parseInt(m.group(1));
        var last = m.group(2) == null ? first : Integer.parseInt(m.group(2));
        if (first < 1024 || last > 65535 || last < first)
            throw new IllegalArgumentException("port range must be inside 1024-65535 and ascending: " + spec);
        return new int[] { first, last };
    }

    public static long parseSince(String s) {
        var m = SINCE.matcher(s.trim().toLowerCase());
        if (!m.matches()) throw new IllegalArgumentException("bad --since (use 30s,5m,2h,7d): " + s);
        var n = Long.parseLong(m.group(1));
        return switch (m.group(2)) {
            case "s" -> n * 1000L;
            case "m" -> n * 60_000L;
            case "h" -> n * 3_600_000L;
            default  -> n * 86_400_000L;
        };
    }

    public static boolean secret(String header) {
        var h = header.toLowerCase();
        return h.contains("cookie") || h.contains("authorization") || h.contains("token")
            || h.contains("sapisid") || h.contains("sessionid") || h.equals("x-goog-authuser");
    }

    public static String mask(String v) { return "<redacted len=" + v.length() + ">"; }

    /** Parse a stored headers JSON string into an object, masking secret values unless reveal. */
    public static JsonNode headers(String json, boolean reveal) {
        var node = tryParse(json);
        if (node == null || !node.isObject()) return TextNode.valueOf(String.valueOf(json));
        if (reveal) return node;
        var out = OM.createObjectNode();
        node.fields().forEachRemaining(e -> out.put(e.getKey(),
            secret(e.getKey()) ? mask(e.getValue().asText()) : e.getValue().asText()));
        return out;
    }

    /** JSON text -> tree, else null (not an object/array, or unparseable). */
    public static JsonNode tryParse(String s) {
        if (s == null) return null;
        var t = s.trim();
        if (t.isEmpty() || !(t.startsWith("{") || t.startsWith("["))) return null;
        try { return OM.readTree(t); } catch (Exception e) { return null; }
    }
}
