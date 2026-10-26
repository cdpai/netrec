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

    static final String EXT_SCHEME = "chrome-extension://";
    static final Set<String> EXT_TYPES = Set.of("service_worker", "background_page", "page");

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
     * The gate-mode equivalent of tabs(port)/json/list -- reshapes Target.getTargets' targetInfos
     * (targetId/type/url/title/...) into the same array-of-{id,type,url,title} shape everything
     * downstream (pages/pickTabs/hits/inventory) already expects, so none of it needs to know
     * which transport is in use.
     */
    public static JsonNode targetsViaLink(CdpLink link) {
        var result = link.call(null, "Target.getTargets", J.obj());
        var out = J.OM.createArrayNode();
        for (var t : result.path("targetInfos"))
            out.add(J.obj().put("id", t.path("targetId").asText())
                .put("type", t.path("type").asText())
                .put("url", t.path("url").asText())
                .put("title", t.path("title").asText()));
        return out;
    }

    /**
     * http targets, in `netrec tabs` order (so --tab <index> means the same thing).
     * Deliberately does NOT require webSocketDebuggerUrl: Chrome omits it once a debugger is attached,
     * and we attach by targetId over the browser connection anyway -- filtering on it would hide our own tabs.
     */
    public static List<JsonNode> pages(JsonNode tabs) { return pages(tabs, false); }

    public static List<JsonNode> pages(JsonNode tabs, boolean extensions) {
        var out = new ArrayList<JsonNode>();
        if (!tabs.isArray()) return out;
        for (var t : tabs)
            if (t.path("id").isTextual() && listed(t, extensions)) out.add(t);
        return out;
    }

    /** An http target; with extensions on, also an extension's service worker, background page or page. Takes a /json/list entry or a targetInfo. */
    public static boolean listed(JsonNode t, boolean extensions) {
        return t.path("url").asText("").startsWith("http") || (extensions && isExtension(t));
    }

    public static boolean isExtension(JsonNode t) {
        return t.path("url").asText("").startsWith(EXT_SCHEME) && EXT_TYPES.contains(t.path("type").asText());
    }

    /** The 32-letter extension id of a chrome-extension:// url, or "". */
    public static String extensionId(String url) {
        if (!url.startsWith(EXT_SCHEME)) return "";
        var rest = url.substring(EXT_SCHEME.length());
        var end = rest.indexOf('/');
        return end < 0 ? rest : rest.substring(0, end);
    }

    /** Every page matching: index, url/title substring, all pages, or the first page when match is null. */
    public static List<JsonNode> pickTabs(JsonNode tabs, String match, boolean all) { return pickTabs(tabs, match, all, false); }

    public static List<JsonNode> pickTabs(JsonNode tabs, String match, boolean all, boolean extensions) {
        var pages = pages(tabs, extensions);
        if (pages.isEmpty()) return List.of();
        if (all) return pages.stream().filter(t -> "page".equals(t.path("type").asText()) || isExtension(t)).toList();
        if (match == null) {
            for (var t : pages) if ("page".equals(t.path("type").asText()) && !isExtension(t)) return List.of(t);
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
