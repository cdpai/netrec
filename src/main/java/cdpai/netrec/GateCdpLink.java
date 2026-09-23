package cdpai.netrec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import cdpai.gate.client.GateClient;
import cdpai.gate.client.GateKey;
import cdpai.gate.client.ScopeRequest;

/**
 * One connection to the browser through cdpgate, the default and preferred mode: the same
 * flat-session command/event shape as CdpConn, over cdpgate's pipe instead of an unauthenticated
 * port. With no key, cdpgate identifies netrec from the process tree and asks a human the first
 * time; with a key name, netrec proves possession of its own key -- for the daemon started at logon.
 */
public final class GateCdpLink implements CdpLink {
    final GateClient client;
    final AtomicInteger nextId = new AtomicInteger(1);
    final Map<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    final List<BiConsumer<String, JsonNode>> listeners = new CopyOnWriteArrayList<>();
    final Thread reader;
    volatile boolean alive = true;
    volatile String failure;

    public GateCdpLink(String pipeName, String keyName) { this(pipeName, keyName, ScopeRequest.UNSCOPED); }

    public GateCdpLink(String pipeName, String keyName, ScopeRequest scope) {
        if (scope.isUnscoped()) System.err.println("[netrec] asking cdpgate for every profile and every website: the approval"
            + " will be short and needs the passphrase. Pass --profiles and --domains to ask for less (list them: cdpg profiles)");
        client = GateClient.connect(pipeName, "netrec", scope, keyName == null ? null : GateKey.loadOrCreate(keyName));
        reader = Thread.ofPlatform().name("netrec-gate-reader").start(this::pump);
    }

    void pump() {
        try {
            String raw;
            while ((raw = client.receive()) != null) onMessage(raw);
        } catch (Exception e) {
            failure = String.valueOf(e.getMessage());
        } finally {
            alive = false;
            pending.values().forEach(f -> f.completeExceptionally(
                new IllegalStateException("gate connection closed" + (failure == null ? "" : ": " + failure))));
            pending.clear();
        }
    }

    void onMessage(String text) {
        try {
            var msg = J.OM.readTree(text);
            if (msg.has("id")) { complete(msg); return; }
            var sid = msg.hasNonNull("sessionId") ? msg.get("sessionId").asText() : null;
            for (var l : listeners) l.accept(sid, msg);
        } catch (Exception e) { System.err.println("[netrec] gate: " + e); }
    }

    void complete(JsonNode msg) {
        var f = pending.remove(msg.path("id").asInt());
        if (f == null) return;
        if (msg.has("error")) f.completeExceptionally(new IllegalStateException(msg.path("error").path("message").asText()));
        else f.complete(msg.path("result"));
    }

    @Override public void onEvent(BiConsumer<String, JsonNode> listener) { listeners.add(listener); }

    @Override public CompletableFuture<JsonNode> send(String sessionId, String method, ObjectNode params) {
        var id = nextId.incrementAndGet();
        var f = new CompletableFuture<JsonNode>();
        pending.put(id, f);
        var m = J.obj().put("id", id).put("method", method);
        m.set("params", params == null ? J.obj() : params);
        if (sessionId != null) m.put("sessionId", sessionId);
        if (!alive) {
            pending.remove(id);
            f.completeExceptionally(new IllegalStateException("gate connection is down"));
            return f;
        }
        try { client.send(m.toString()); }
        catch (Exception e) {
            pending.remove(id);
            f.completeExceptionally(e);
        }
        return f;
    }

    @Override public JsonNode call(String sessionId, String method, ObjectNode params) {
        try { return send(sessionId, method, params).get(20, TimeUnit.SECONDS); }
        catch (ExecutionException e) { throw new IllegalStateException(method + ": " + e.getCause().getMessage()); }
        catch (Exception e) { throw new IllegalStateException(method + ": " + e); }
    }

    @Override public boolean alive() { return alive; }

    @Override public void close() {
        alive = false;
        client.cancelPendingIo();
        client.close();
        reader.interrupt();
    }
}
