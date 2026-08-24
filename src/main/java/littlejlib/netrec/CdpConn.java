package littlejlib.netrec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/**
 * One websocket to the browser-level CDP endpoint. Commands are addressed to flat session ids, so a single
 * connection covers every attached tab, iframe and worker. Blocking `call` must not be used from a listener.
 */
public final class CdpConn extends WebSocketListener {
    final int port;
    final OkHttpClient client = new OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(30, TimeUnit.SECONDS).build();
    final AtomicInteger nextId = new AtomicInteger(1);
    final Map<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    final List<BiConsumer<String, JsonNode>> listeners = new CopyOnWriteArrayList<>();
    final CountDownLatch opened = new CountDownLatch(1);
    volatile WebSocket ws;
    volatile String failure;

    public CdpConn(int port) {
        this.port = port;
        var url = Cdp.browserWs(port);
        ws = client.newWebSocket(new Request.Builder().url(url).build(), this);
        try {
            if (!opened.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("CDP websocket did not open on :" + port);
        } catch (InterruptedException e) { throw new IllegalStateException(e); }
        if (failure != null) throw new IllegalStateException("CDP: " + failure);
    }

    public void onEvent(BiConsumer<String, JsonNode> listener) { listeners.add(listener); }

    public CompletableFuture<JsonNode> send(String sessionId, String method, ObjectNode params) {
        var id = nextId.incrementAndGet();
        var f = new CompletableFuture<JsonNode>();
        pending.put(id, f);
        var m = J.obj().put("id", id).put("method", method);
        m.set("params", params == null ? J.obj() : params);
        if (sessionId != null) m.put("sessionId", sessionId);
        var w = ws;
        if (w == null || !w.send(m.toString())) {
            pending.remove(id);
            f.completeExceptionally(new IllegalStateException("CDP connection is down"));
        }
        return f;
    }

    public JsonNode call(String sessionId, String method, ObjectNode params) {
        try { return send(sessionId, method, params).get(20, TimeUnit.SECONDS); }
        catch (ExecutionException e) { throw new IllegalStateException(method + ": " + e.getCause().getMessage()); }
        catch (Exception e) { throw new IllegalStateException(method + ": " + e); }
    }

    @Override public void onOpen(WebSocket w, Response r) { opened.countDown(); }

    @Override public void onMessage(WebSocket w, String text) {
        try {
            var msg = J.OM.readTree(text);
            if (msg.has("id")) { complete(msg); return; }
            var sid = msg.hasNonNull("sessionId") ? msg.get("sessionId").asText() : null;
            for (var l : listeners) l.accept(sid, msg);
        } catch (Exception e) { System.err.println("[netrec] cdp: " + e); }
    }

    void complete(JsonNode msg) {
        var f = pending.remove(msg.path("id").asInt());
        if (f == null) return;
        if (msg.has("error")) f.completeExceptionally(new IllegalStateException(msg.path("error").path("message").asText()));
        else f.complete(msg.path("result"));
    }

    @Override public void onFailure(WebSocket w, Throwable t, Response r) {
        failure = String.valueOf(t.getMessage());
        ws = null;
        opened.countDown();
        pending.values().forEach(f -> f.completeExceptionally(t));
        pending.clear();
        System.err.println("[netrec] CDP connection lost: " + failure);
    }

    @Override public void onClosed(WebSocket w, int code, String reason) { ws = null; }

    public boolean alive() { return ws != null; }

    public void close() {
        var w = ws;
        ws = null;
        if (w != null) w.close(1000, "bye");
        client.dispatcher().executorService().shutdown();
    }
}
