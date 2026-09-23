package cdpai.netrec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns the CDP connection and the recording subscriptions. A failed match costs a command, never the daemon;
 * targets that appear or navigate into a pattern later are adopted automatically, children included.
 */
public final class RecMgr {
    final Store store;
    final Map<String, Sub> subs = new ConcurrentHashMap<>();
    final Map<String, Sub> bySession = new ConcurrentHashMap<>();
    final Map<String, Sub> byTarget = new ConcurrentHashMap<>();
    final Map<String, String> live = new ConcurrentHashMap<>();
    final RecEvents events = new RecEvents(this);
    volatile CdpLink conn;
    volatile String connKey;

    public RecMgr(Store store) { this.store = store; }

    public synchronized ObjectNode start(String name, String tab, boolean all, boolean children,
                                         ConnSpec spec, int maxBody, long waitMs) {
        var sub = new Sub(name == null ? "rec-" + System.currentTimeMillis() : name, tab, all, children, spec, maxBody);
        if (subs.containsKey(sub.name))
            throw new IllegalStateException("session '" + sub.name + "' is already recording (netrec rec status)");
        ensureConn(spec);
        sub.capture = new Capture(store, conn, sub.name, maxBody);
        subs.put(sub.name, sub);
        var tabs = tabsFor(spec);
        var byIndex = tab != null && tab.matches("\\d+") && Integer.parseInt(tab) < Cdp.pages(tabs).size();
        sub.adopt = all || (tab != null && !byIndex);
        var matched = Cdp.pickTabs(tabs, tab, all).stream().map(t -> t.path("id").asText()).toList();
        var free = matched.stream().filter(id -> byTarget.get(id) == null).toList();
        var taken = matched.stream().map(byTarget::get).filter(s -> s != null && s != sub).map(s -> s.name).distinct().toList();
        var ours = byTarget.containsValue(sub);
        free.forEach(id -> claim(sub, id));
        var expecting = !free.isEmpty() || ours || !sub.cdpSessions.isEmpty();
        sub.await(expecting ? Math.max(waitMs, 8000) : waitMs);
        if (!sub.cdpSessions.isEmpty() || waitMs > 0) return sub.json();
        drop(sub);
        throw new IllegalStateException(expecting
            ? "attach failed" + (sub.lastError == null ? " (no session came up)" : ": " + sub.lastError)
            : !taken.isEmpty()
                ? "every matching tab is already recorded by session " + taken
                    + " -- query that session, or: netrec rec stop -s " + taken.get(0)
                : "no tab matches " + (tab == null ? "(default: first page)" : "'" + tab + "'")
                    + " via " + spec.key() + " -- seen: " + inventory(tabs)
                    + "  (netrec tabs, or pass --wait <seconds> to sit until one appears)");
    }

    public static JsonNode tabsFor(ConnSpec spec) {
        if (!spec.gate()) return Cdp.tabs(spec.port());
        try (var link = new GateCdpLink(spec.pipeName(), spec.keyName(), spec.scope())) { return Cdp.targetsViaLink(link); }
    }

    /**
     * Read the browser's cookie jar. Borrows the recorder's CDP connection when one is already open on this
     * connection, otherwise opens a throwaway one and closes it again — so asking for a cookie never leaves a
     * connection behind and never disturbs a recording in progress.
     */
    public synchronized ObjectNode cookies(ConnSpec spec, String url, String domain, String name, boolean reveal) {
        var borrowed = conn != null && conn.alive() && spec.key().equals(connKey);
        var c = borrowed ? conn : spec.open();
        try {
            var out = Cookies.shape(spec.gate() ? jarViaTabs(c) : c.call(null, "Storage.getCookies", J.obj()), url, domain, name, reveal);
            return out.put("conn", spec.key());
        } finally {
            if (!borrowed) c.close();
        }
    }

    /**
     * Every approved profile's jar, read through one of its own tabs. Storage.getCookies reads whichever profile the
     * browser last used -- it moves as the human clicks between windows, so it is the wrong jar as often as not (PRP 05);
     * Network.getAllCookies on a tab's session reads that tab's profile, always. Through cdpgate, Target.getTargets lists
     * only the approved profiles' tabs, so one tab per browserContextId covers exactly the approved jars.
     */
    static JsonNode jarViaTabs(CdpLink c) {
        var merged = J.obj();
        var all = merged.putArray("cookies");
        var seen = new HashSet<String>();
        for (var t : c.call(null, "Target.getTargets", J.obj()).path("targetInfos")) {
            if (!t.path("type").asText().equals("page") || !t.path("url").asText().startsWith("http")) continue;
            if (!seen.add(t.path("browserContextId").asText(""))) continue;
            var sid = c.call(null, "Target.attachToTarget", J.obj().put("targetId", t.path("targetId").asText()).put("flatten", true))
                .path("sessionId").asText(null);
            if (sid == null) continue;
            try { c.call(sid, "Network.getAllCookies", J.obj()).path("cookies").forEach(all::add); }
            finally { c.send(null, "Target.detachFromTarget", J.obj().put("sessionId", sid)); }
        }
        if (seen.isEmpty()) throw new IllegalStateException("no open web page in the approved profile(s) to read cookies through"
            + " -- open any page in that profile, then retry");
        return merged;
    }

    public synchronized ObjectNode stop(String name, boolean all) {
        var doomed = new ArrayList<Sub>();
        if (all) doomed.addAll(subs.values());
        else if (name != null) {
            var s = subs.get(name);
            if (s == null) throw new IllegalStateException("no such recording session: " + name);
            doomed.add(s);
        } else if (subs.size() == 1) doomed.addAll(subs.values());
        else if (subs.isEmpty()) throw new IllegalStateException("nothing is recording");
        else throw new IllegalStateException("several sessions are recording -- pass --session <name> or --all");
        var out = J.obj();
        var arr = out.putArray("stopped");
        for (var s : doomed) { arr.add(s.json()); drop(s); }
        return out;
    }

    public void stopAll() { if (!subs.isEmpty()) stop(null, true); }

    public ObjectNode status() {
        var out = J.obj().put("conn", conn == null ? "" : connKey)
            .put("connected", conn != null && conn.alive());
        var arr = out.putArray("sessions");
        subs.values().stream().sorted(Comparator.comparing(s -> s.name)).forEach(s -> arr.add(s.json()));
        return out;
    }

    public boolean busy() { return subs.values().stream().anyMatch(s -> !"stopped".equals(s.state)); }

    public boolean recording(String name) { return subs.containsKey(name); }

    public String currentSession() {
        var live = subs.values().stream().filter(s -> "recording".equals(s.state)).toList();
        return live.size() == 1 ? live.get(0).name : null;
    }

    static String inventory(JsonNode tabs) {
        if (!tabs.isArray()) return "no target list (" + Capture.shortUrl(tabs.toString()) + ")";
        var out = new ArrayList<String>();
        for (var t : tabs) out.add(t.path("type").asText("?") + " " + t.path("url").asText(""));
        return out.isEmpty() ? "0 targets" : out.size() + " targets [" + String.join(" | ", out) + "]";
    }

    void ensureConn(ConnSpec spec) {
        if (conn != null && !conn.alive()) {
            conn.close();
            conn = null;
            subs.values().forEach(s -> { s.state = "lost"; s.cdpSessions.clear(); s.targets.clear(); });
            bySession.clear();
            byTarget.clear();
            live.clear();
        }
        if (conn != null && !spec.key().equals(connKey))
            throw new IllegalStateException("already attached via " + connKey + " -- stop those sessions first");
        if (conn == null) {
            var c = spec.open();
            c.onEvent(events::onEvent);
            conn = c;
            connKey = spec.key();
            c.send(null, "Target.setDiscoverTargets", J.obj().put("discover", true));
        }
    }

    void claim(Sub sub, String targetId) {
        byTarget.put(targetId, sub);
        conn.send(null, "Target.attachToTarget", J.obj().put("targetId", targetId).put("flatten", true))
            .whenComplete((res, err) -> {
                if (err == null) return;
                byTarget.remove(targetId);
                sub.lastError = err.getMessage();
            });
    }

    void drop(Sub sub) {
        sub.state = "stopped";
        if (sub.capture != null) sub.capture.flush();
        for (var sid : sub.cdpSessions) {
            bySession.remove(sid);
            live.entrySet().removeIf(e -> e.getValue().equals(sid));
            if (conn != null) conn.send(null, "Target.detachFromTarget", J.obj().put("sessionId", sid));
        }
        sub.cdpSessions.clear();
        sub.targets.clear();
        byTarget.entrySet().removeIf(e -> e.getValue() == sub);
        subs.remove(sub.name);
        if (subs.isEmpty() && conn != null) { conn.close(); conn = null; }
    }
}
