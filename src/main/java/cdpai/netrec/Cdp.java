package cdpai.netrec;

import com.fasterxml.jackson.databind.*;
import org.brotli.dec.BrotliInputStream;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** Low-level Chrome DevTools Protocol helpers (HTTP discovery + body decoding). */
public final class Cdp {
    private Cdp() {}

    public static JsonNode tabs(int port) {
        try { return J.OM.readTree(httpGet("http://127.0.0.1:" + port + "/json/list")); }
        catch (ConnectException e) {
            throw new RuntimeException("No Chrome debug endpoint on :" + port
                + ". Start the browser with --remote-debugging-port=" + port);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    /** The browser-level websocket: one connection, flat sessions, every target reachable. */
    public static String browserWs(int port) {
        try {
            var v = J.OM.readTree(httpGet("http://127.0.0.1:" + port + "/json/version"));
            var ws = v.path("webSocketDebuggerUrl").asText(null);
            if (ws == null) throw new IllegalStateException("no browser-level websocket on :" + port);
            return ws;
        } catch (ConnectException e) {
            throw new IllegalStateException("No Chrome debug endpoint on :" + port
                + ". Start the browser with --remote-debugging-port=" + port + "  (netrec config --detect)");
        } catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException(String.valueOf(e.getMessage())); }
    }

    /**
     * http targets, in `netrec tabs` order (so --tab <index> means the same thing).
     * Deliberately does NOT require webSocketDebuggerUrl: Chrome omits it once a debugger is attached,
     * and we attach by targetId over the browser connection anyway -- filtering on it would hide our own tabs.
     */
    public static List<JsonNode> pages(JsonNode tabs) {
        var out = new ArrayList<JsonNode>();
        if (!tabs.isArray()) return out;
        for (var t : tabs)
            if (t.path("id").isTextual() && t.path("url").asText("").startsWith("http")) out.add(t);
        return out;
    }

    /** Every page matching: index, url/title substring, all pages, or the first page when match is null. */
    public static List<JsonNode> pickTabs(JsonNode tabs, String match, boolean all) {
        var pages = pages(tabs);
        if (pages.isEmpty()) return List.of();
        if (all) return pages.stream().filter(t -> "page".equals(t.path("type").asText())).toList();
        if (match == null) {
            for (var t : pages) if ("page".equals(t.path("type").asText())) return List.of(t);
            return List.of(pages.get(0));
        }
        if (match.matches("\\d+")) {
            var i = Integer.parseInt(match);
            if (i >= 0 && i < pages.size()) return List.of(pages.get(i));
        }
        return pages.stream().filter(t -> hits(t, match)).toList();
    }

    public static boolean hits(JsonNode target, String match) {
        var m = match.toLowerCase();
        return target.path("url").asText("").toLowerCase().contains(m)
            || target.path("title").asText("").toLowerCase().contains(m);
    }

    public static byte[] collectPostData(JsonNode request) {
        var entries = request.path("postDataEntries");
        if (entries.isArray() && entries.size() > 0) {
            try (var out = new ByteArrayOutputStream()) {
                for (var e : entries) {
                    var b = e.path("bytes").asText("");
                    if (!b.isEmpty()) out.write(Base64.getDecoder().decode(b));
                }
                var arr = out.toByteArray();
                return arr.length > 0 ? arr : null;
            } catch (Exception ex) { return null; }
        }
        var pd = request.path("postData").asText(null);
        return pd != null ? pd.getBytes(StandardCharsets.UTF_8) : null;
    }

    public static String decompress(byte[] bytes, String enc) {
        enc = enc == null ? "" : enc.toLowerCase();
        try {
            if (enc.contains("gzip")) return new String(gunzip(bytes), StandardCharsets.UTF_8);
            if (enc.contains("br")) return brotli(bytes);
        } catch (Exception ignore) {}
        try { return new String(gunzip(bytes), StandardCharsets.UTF_8); } catch (Exception ignore) {}
        try { return brotli(bytes); } catch (Exception ignore) {}
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static String decodeBody(JsonNode result) {
        var body = result.path("body").asText("");
        if (result.path("base64Encoded").asBoolean(false))
            return decompress(Base64.getDecoder().decode(body), null);
        return body;
    }

    public static String headerCI(JsonNode headers, String name) {
        var it = headers.fields();
        while (it.hasNext()) {
            var e = it.next();
            if (e.getKey().equalsIgnoreCase(name)) return e.getValue().asText();
        }
        return null;
    }

    public static String host(String url) {
        try { return new URI(url).getHost() == null ? "" : new URI(url).getHost(); }
        catch (Exception e) { return ""; }
    }

    public static String path(String url) {
        try { var p = new URI(url).getRawPath(); return p == null ? "" : p; }
        catch (Exception e) { return ""; }
    }

    static byte[] gunzip(byte[] b) throws IOException {
        try (var g = new GZIPInputStream(new ByteArrayInputStream(b)); var o = new ByteArrayOutputStream()) {
            g.transferTo(o); return o.toByteArray();
        }
    }

    static String brotli(byte[] b) throws IOException {
        try (var i = new BrotliInputStream(new ByteArrayInputStream(b)); var o = new ByteArrayOutputStream()) {
            i.transferTo(o); return o.toString(StandardCharsets.UTF_8);
        }
    }

    static String httpGet(String url) throws Exception {
        var c = (HttpURLConnection) new URI(url).toURL().openConnection();
        c.setConnectTimeout(5000); c.setReadTimeout(5000);
        try (var is = c.getInputStream()) { return new String(is.readAllBytes(), StandardCharsets.UTF_8); }
    }
}
