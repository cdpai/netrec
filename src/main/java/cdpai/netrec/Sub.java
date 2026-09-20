package cdpai.netrec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.concurrent.*;

/** One named recording subscription: a target pattern the daemon keeps satisfied, not a process you are locked to. */
public final class Sub {
    final String name, pattern;
    final boolean all, children;
    final int port, maxBody;
    final long startedAt = System.currentTimeMillis();
    final Set<String> cdpSessions = ConcurrentHashMap.newKeySet();
    final Map<String, String[]> targets = new ConcurrentHashMap<>();
    final CountDownLatch firstAttach = new CountDownLatch(1);
    Capture capture;
    volatile String state = "waiting", lastError;
    volatile boolean adopt;

    public Sub(String name, String pattern, boolean all, boolean children, int port, int maxBody) {
        this.name = name; this.pattern = pattern; this.all = all;
        this.children = children; this.port = port; this.maxBody = maxBody;
    }

    void attach(String sid, String type, String url) {
        cdpSessions.add(sid);
        targets.put(sid, new String[] { type, url });
        state = "recording";
        firstAttach.countDown();
    }

    void detach(String sid) {
        cdpSessions.remove(sid);
        targets.remove(sid);
        if (capture != null) capture.forgetTarget(sid);
        if (cdpSessions.isEmpty() && "recording".equals(state)) state = "waiting";
    }

    void await(long ms) {
        if (ms <= 0) return;
        try { firstAttach.await(ms, TimeUnit.MILLISECONDS); } catch (InterruptedException ignore) {}
    }

    /** Should a target that appeared (or navigated) after start be adopted by this subscription? */
    boolean wants(JsonNode targetInfo) {
        if (!adopt || "stopped".equals(state)) return false;
        if (!"page".equals(targetInfo.path("type").asText())) return false;
        if (!targetInfo.path("url").asText("").startsWith("http")) return false;
        return all || (pattern != null && Cdp.hits(targetInfo, pattern));
    }

    ObjectNode json() {
        var n = J.obj().put("session", name).put("state", state)
            .put("tab", pattern == null ? "" : pattern).put("all", all).put("children", children)
            .put("port", port).put("since", startedAt)
            .put("records", capture == null ? 0 : capture.records());
        if (lastError != null) n.put("error", lastError);
        var arr = n.putArray("targets");
        targets.values().forEach(d -> arr.add(J.obj().put("type", d[0]).put("url", d[1])));
        return n;
    }
}
