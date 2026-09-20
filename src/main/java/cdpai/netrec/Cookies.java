package cdpai.netrec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.util.*;

/**
 * The browser's cookie jar, read over CDP.
 *
 * <p>Recording answers "what did the browser send"; this answers "what is the browser holding". They are not the
 * same question: a session cookie only appears in captured traffic when a request happens to go out while the
 * recorder is attached, so recovering an auth cookie used to mean asking a human to reload a page. Reading the jar
 * needs no traffic at all, and reports the expiry — which is usually the reason the credential stopped working.
 */
public final class Cookies {
    private Cookies() {}

    /** Filter + shape a CDP Storage.getCookies result. Values are masked unless reveal. */
    public static ObjectNode shape(JsonNode result, String url, String domain, String name, boolean reveal) {
        var host = host(url);
        var out = J.obj();
        var arr = out.putArray("cookies");
        var now = System.currentTimeMillis();
        var rows = new ArrayList<ObjectNode>();
        for (var c : result.path("cookies")) {
            var cd = c.path("domain").asText("");
            var cn = c.path("name").asText("");
            if (host != null && !hostMatches(host, cd)) continue;
            if (domain != null && !cd.toLowerCase().contains(domain.toLowerCase())) continue;
            if (name != null && !cn.toLowerCase().contains(name.toLowerCase())) continue;
            rows.add(row(c, cn, cd, now, reveal));
        }
        rows.sort(Comparator.comparing((ObjectNode n) -> n.path("domain").asText())
            .thenComparing(n -> n.path("name").asText()));
        rows.forEach(arr::add);
        out.put("matched", rows.size()).put("total", result.path("cookies").size());
        return out;
    }

    static ObjectNode row(JsonNode c, String name, String domain, long now, boolean reveal) {
        var value = c.path("value").asText("");
        // CDP gives expires in (fractional) epoch SECONDS, and -1 for a session cookie.
        var expSec = c.path("expires").asDouble(-1);
        var expMs = expSec <= 0 ? 0L : (long) (expSec * 1000);
        var n = J.obj().put("name", name).put("domain", domain).put("path", c.path("path").asText("/"))
            .put("value", reveal ? value : J.mask(value)).put("bytes", value.length());
        if (expMs == 0) n.put("expires", "session");
        else {
            n.put("expires", J.iso(expMs));
            n.put("expired", expMs <= now);
            n.put(expMs <= now ? "expiredAgo" : "expiresIn", J.duration(Math.abs(expMs - now)));
        }
        n.put("secure", c.path("secure").asBoolean(false)).put("httpOnly", c.path("httpOnly").asBoolean(false));
        var same = c.path("sameSite").asText("");
        if (!same.isEmpty()) n.put("sameSite", same);
        return n;
    }

    /** Cookie-jar host matching: exact host, or a dot-domain the host sits under. */
    static boolean hostMatches(String host, String domain) {
        if (domain.isEmpty()) return false;
        var d = domain.startsWith(".") ? domain.substring(1) : domain;
        return host.equalsIgnoreCase(d) || host.toLowerCase().endsWith("." + d.toLowerCase());
    }

    /** Host of --url; a bare host or domain substring is accepted too, so `--url portal.example.com` works. */
    static String host(String url) {
        if (url == null || url.isBlank()) return null;
        var u = url.strip();
        if (!u.contains("://")) u = "https://" + u;
        try {
            var h = new URI(u).getHost();
            return h == null || h.isBlank() ? null : h;
        } catch (Exception e) { throw new IllegalArgumentException("bad --url: " + url); }
    }
}
