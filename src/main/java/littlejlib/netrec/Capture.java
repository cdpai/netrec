package littlejlib.netrec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Assembles CDP network events into ReqRec rows for one recording session, across all its attached targets. */
public final class Capture {
    static final Set<String> BODY_TYPES = Set.of(
        "Document", "XHR", "Fetch", "Script", "Stylesheet", "Manifest", "EventSource");
    static final Set<String> LOUD = Set.of("Document", "XHR", "Fetch", "EventSource");

    final Store store;
    final CdpConn conn;
    final String session;
    final int maxBody;
    final Map<String, ObjectNode> open = new ConcurrentHashMap<>();
    final Map<String, JsonNode> extra = new ConcurrentHashMap<>();
    final Set<String> written = ConcurrentHashMap.newKeySet();
    final Map<String, String[]> targets = new ConcurrentHashMap<>();
    final AtomicInteger seq = new AtomicInteger(), count = new AtomicInteger();

    public Capture(Store store, CdpConn conn, String session, int maxBody) {
        this.store = store; this.conn = conn; this.session = session; this.maxBody = maxBody;
    }

    public void noteTarget(String sid, String url, String type) { targets.put(sid, new String[] { url, type }); }
    public void forgetTarget(String sid) { targets.remove(sid); }
    public int records() { return count.get(); }

    public void onEvent(String sid, JsonNode msg) {
        var p = msg.path("params");
        switch (msg.path("method").asText("")) {
            case "Network.requestWillBeSent" -> onRequest(sid, p);
            case "Network.requestWillBeSentExtraInfo" -> onExtra(sid, p);
            case "Network.responseReceived" -> onResponse(sid, p);
            case "Network.loadingFinished" -> onFinished(sid, p);
            case "Network.loadingFailed" -> onFailed(sid, p);
            default -> {}
        }
    }

    String key(String sid, JsonNode p) { return sid + "|" + p.path("requestId").asText(); }

    void onRequest(String sid, JsonNode p) {
        var request = p.path("request");
        var url = request.path("url").asText();
        var k = key(sid, p);
        var n = J.obj();
        n.put("id", session + ":" + seq.incrementAndGet());
        n.put("session", session);
        n.put("requestId", p.path("requestId").asText());
        n.put("ts", System.currentTimeMillis());
        n.put("method", request.path("method").asText());
        n.put("url", url);
        n.put("host", Cdp.host(url));
        n.put("path", Cdp.path(url));
        n.put("resourceType", p.path("type").asText(""));
        n.put("state", "pending");
        n.put("reqHeaders", request.path("headers").toString());
        var t = targets.get(sid);
        if (t != null) { n.put("target", t[0]); n.put("targetType", t[1]); }
        var body = Cdp.collectPostData(request);
        if (body != null)
            n.put("reqBody", cap(Cdp.decompress(body, Cdp.headerCI(request.path("headers"), "content-encoding")), n, "reqBodyTruncated"));
        open.put(k, n);
        var ex = extra.remove(k);
        if (ex != null) n.put("reqExtraHeaders", ex.toString());
    }

    void onExtra(String sid, JsonNode p) {
        var k = key(sid, p);
        var n = open.get(k);
        if (n != null) n.put("reqExtraHeaders", p.path("headers").toString());
        else extra.put(k, p.path("headers"));
    }

    void onResponse(String sid, JsonNode p) {
        var n = open.get(key(sid, p));
        if (n == null) return;
        var resp = p.path("response");
        n.put("status", resp.path("status").asInt());
        n.put("mimeType", resp.path("mimeType").asText(""));
        n.put("remoteIp", resp.path("remoteIPAddress").asText(""));
        n.put("respHeaders", resp.path("headers").toString());
        if (p.path("type").isTextual()) n.put("resourceType", p.path("type").asText());
    }

    void onFinished(String sid, JsonNode p) {
        var k = key(sid, p);
        var n = open.get(k);
        if (n == null) return;
        if (BODY_TYPES.contains(n.path("resourceType").asText())) fetchBody(sid, p.path("requestId").asText(), k);
        else finish(k);
    }

    void onFailed(String sid, JsonNode p) {
        var k = key(sid, p);
        var n = open.get(k);
        if (n == null) return;
        n.put("state", "failed");
        n.put("errorText", p.path("errorText").asText(""));
        finish(k);
    }

    void fetchBody(String sid, String requestId, String k) {
        conn.send(sid, "Network.getResponseBody", J.obj().put("requestId", requestId))
            .whenComplete((res, err) -> {
                var n = open.get(k);
                if (n != null) {
                    if (err != null || res == null || !res.has("body")) n.put("respBodyUnavailable", true);
                    else n.put("respBody", cap(Cdp.decodeBody(res), n, "respBodyTruncated"));
                }
                finish(k);
            });
    }

    void finish(String k) {
        var n = open.remove(k);
        if (n == null || !written.add(n.path("id").asText())) return;
        if ("pending".equals(n.path("state").asText())) n.put("state", "complete");
        try { store.insert("ReqRec", n); }
        catch (Exception e) { System.err.println("[netrec] write failed: " + e.getMessage()); return; }
        var c = count.incrementAndGet();
        if (LOUD.contains(n.path("resourceType").asText()) || c % 50 == 0)
            System.err.println("[rec:" + session + "] " + n.path("status").asText("?") + " "
                + n.path("method").asText() + " " + shortUrl(n.path("url").asText("")));
    }

    /** Write whatever is still in flight (called on stop) so nothing is silently dropped. */
    public void flush() { new ArrayList<>(open.keySet()).forEach(this::finish); }

    String cap(String s, ObjectNode n, String flag) {
        if (s != null && s.length() > maxBody) { n.put(flag, true); return s.substring(0, maxBody); }
        return s;
    }

    static String shortUrl(String u) { return u.length() > 100 ? u.substring(0, 100) + "..." : u; }
}
