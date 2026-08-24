package littlejlib.netrec;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;

/**
 * Routes CDP events to the right subscription. Runs on the websocket reader thread, so it never issues a
 * blocking CDP call: attachment is completed when the Target.attachedToTarget event arrives, which is also
 * how auto-attached children (iframes, workers, service workers) are picked up.
 */
public final class RecEvents {
    static final boolean DEBUG = "1".equals(System.getenv("NETREC_DEBUG"));

    final RecMgr m;

    public RecEvents(RecMgr m) { this.m = m; }

    void onEvent(String sid, JsonNode msg) {
        var method = msg.path("method").asText("");
        if (DEBUG && method.startsWith("Target."))
            System.err.println("[cdp] " + method + " " + msg.path("params").path("targetInfo").path("url").asText(""));
        if (method.startsWith("Network.")) {
            var sub = m.bySession.get(sid);
            if (sub != null) sub.capture.onEvent(sid, msg);
            return;
        }
        switch (method) {
            case "Target.attachedToTarget" -> onAttached(sid, msg.path("params"));
            case "Target.detachedFromTarget" -> onDetached(msg.path("params"));
            case "Target.targetCreated", "Target.targetInfoChanged" -> onDiscovered(msg.path("params").path("targetInfo"));
            default -> {}
        }
    }

    void onAttached(String parentSid, JsonNode p) {
        var child = p.path("sessionId").asText();
        var info = p.path("targetInfo");
        var targetId = info.path("targetId").asText();
        var type = info.path("type").asText("");
        var url = info.path("url").asText("");
        var sub = m.byTarget.get(targetId);
        if (sub == null && parentSid != null) sub = m.bySession.get(parentSid);
        if (sub == null || "stopped".equals(sub.state) || m.live.containsKey(targetId)) {
            m.conn.send(null, "Target.detachFromTarget", J.obj().put("sessionId", child));
            return;
        }
        m.byTarget.put(targetId, sub);
        m.live.put(targetId, child);
        m.bySession.put(child, sub);
        sub.attach(child, type, url);
        sub.capture.noteTarget(child, url, type);
        try {
            watch("Network.enable[" + type + "]",
                m.conn.send(child, "Network.enable", J.obj().put("maxPostDataSize", 5_000_000)));
            if (sub.children)
                watch("Target.setAutoAttach[" + type + "]", m.conn.send(child, "Target.setAutoAttach", J.obj()
                    .put("autoAttach", true).put("waitForDebuggerOnStart", true).put("flatten", true)));
        } finally {
            if (p.path("waitingForDebugger").asBoolean(false))
                m.conn.send(child, "Runtime.runIfWaitingForDebugger", null);
        }
        System.err.println("[netrec] " + sub.name + " + " + type + " " + Capture.shortUrl(url));
    }

    void onDetached(JsonNode p) {
        var sid = p.path("sessionId").asText();
        var sub = m.bySession.remove(sid);
        if (sub != null) sub.detach(sid);
        m.live.entrySet().removeIf(e -> e.getValue().equals(sid));
        var targetId = p.path("targetId").asText(null);
        if (targetId != null) m.byTarget.remove(targetId);
    }

    /** A target is adopted by one subscription only (double-recording would duplicate rows).
     *  Order is deterministic: a session still waiting for its first target wins, then the oldest. */
    void onDiscovered(JsonNode info) {
        var targetId = info.path("targetId").asText(null);
        if (targetId == null || m.byTarget.containsKey(targetId)) return;
        m.subs.values().stream().filter(s -> s.wants(info))
            .sorted(Comparator.comparing((Sub s) -> !s.cdpSessions.isEmpty()).thenComparing(s -> s.startedAt))
            .findFirst().ifPresent(sub -> m.claim(sub, targetId));
    }

    static void watch(String what, CompletableFuture<JsonNode> f) {
        f.whenComplete((res, err) -> {
            if (err != null) System.err.println("[netrec] " + what + " failed: " + err.getMessage());
            else if (DEBUG) System.err.println("[cdp] ok " + what);
        });
    }
}
