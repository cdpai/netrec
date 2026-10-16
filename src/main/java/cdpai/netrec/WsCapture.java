package cdpai.netrec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Optional WebSocket recording for one session. The Network.webSocket* events are passive notifications on a session
 * that is already attached, so nothing is sent on the socket and the page cannot tell. Rows are queued from the CDP
 * reader thread and written by one writer thread in batches, so a chatty socket never stalls the reader.
 */
public final class WsCapture {
    static final ObjectNode STOP = J.obj();

    final Capture c;
    final Map<String, String> urls = new ConcurrentHashMap<>();
    final Map<String, Long> opened = new ConcurrentHashMap<>();
    final Map<String, JsonNode> handshakes = new ConcurrentHashMap<>();
    final BlockingQueue<ObjectNode> queue = new LinkedBlockingQueue<>();
    final AtomicInteger seq = new AtomicInteger();
    final Thread writer;

    public WsCapture(Capture c) {
        this.c = c;
        writer = Thread.ofPlatform().name("netrec-ws-" + c.session).daemon().start(this::drain);
    }

    void onEvent(String sid, JsonNode msg) {
        var p = msg.path("params");
        var k = sid + "|" + p.path("requestId").asText();
        switch (msg.path("method").asText("")) {
            case "Network.webSocketCreated" -> { urls.put(k, p.path("url").asText("")); opened.put(k, System.currentTimeMillis()); emit(row(sid, k, "open")); }
            case "Network.webSocketWillSendHandshakeRequest" -> handshakes.put(k, p.path("request").path("headers"));
            case "Network.webSocketHandshakeResponseReceived" -> onHandshake(sid, k, p.path("response"));
            case "Network.webSocketFrameSent" -> onFrame(sid, k, "sent", p.path("response"));
            case "Network.webSocketFrameReceived" -> onFrame(sid, k, "received", p.path("response"));
            case "Network.webSocketFrameError" -> emit(row(sid, k, "error").put("errorText", p.path("errorMessage").asText("")));
            case "Network.webSocketClosed" -> { emit(row(sid, k, "close")); urls.remove(k); opened.remove(k); handshakes.remove(k); }
            default -> {}
        }
    }

    void onHandshake(String sid, String k, JsonNode resp) {
        var n = row(sid, k, "handshake");
        n.put("status", resp.path("status").asInt());
        n.put("respHeaders", resp.path("headers").toString());
        var req = handshakes.remove(k);
        if (req != null) n.put("reqHeaders", req.toString());
        emit(n);
    }

    void onFrame(String sid, String k, String dir, JsonNode frame) {
        var opcode = frame.path("opcode").asInt();
        var data = frame.path("payloadData").asText("");
        var n = row(sid, k, dir);
        n.put("opcode", opcode);
        n.put("mask", frame.path("mask").asBoolean(false));
        var bytes = opcode == 2 ? decode(data) : data.getBytes(StandardCharsets.UTF_8);
        n.put("size", bytes.length);
        n.put("preview", opcode == 2 ? hex(bytes) : data.substring(0, Math.min(data.length(), 200)));
        if (data.length() > c.maxBody) { n.put("payloadTruncated", true); data = data.substring(0, c.maxBody); }
        n.put("payload", data);
        emit(n);
    }

    ObjectNode row(String sid, String k, String dir) {
        var n = J.obj();
        var i = seq.incrementAndGet();
        var url = urls.getOrDefault(k, "");
        n.put("id", c.session + ":ws" + i).put("session", c.session).put("n", i).put("ts", System.currentTimeMillis());
        n.put("socket", k).put("dir", dir).put("url", url).put("host", Cdp.host(url));
        var o = opened.get(k);
        if (o != null) n.put("socketOpenedAt", o.longValue());
        var t = c.targets.get(sid);
        if (t != null) { n.put("target", t[0]); n.put("targetType", t[1]); }
        return n;
    }

    void emit(ObjectNode n) { queue.add(n); }

    void drain() {
        try {
            for (var stop = false; !stop; ) {
                var batch = new ArrayList<ObjectNode>();
                batch.add(queue.take());
                queue.drainTo(batch, 199);
                stop = batch.removeIf(n -> n == STOP);
                write(batch);
            }
        } catch (InterruptedException ignore) {}
    }

    void write(List<ObjectNode> batch) {
        if (batch.isEmpty()) return;
        try { c.store.insertAll("WsFrame", batch); c.wsCount.addAndGet(batch.size()); }
        catch (Exception e) { System.err.println("[netrec] ws write failed: " + e.getMessage()); }
    }

    /** Write everything queued so far and stop the writer (called on stop). */
    void flush() {
        queue.add(STOP);
        try { writer.join(10_000); } catch (InterruptedException ignore) {}
    }

    static byte[] decode(String b64) {
        try { return Base64.getDecoder().decode(b64); } catch (IllegalArgumentException e) { return new byte[0]; }
    }

    static String hex(byte[] b) {
        var s = new StringBuilder();
        for (var i = 0; i < Math.min(b.length, 64); i++) s.append(String.format("%02x", b[i] & 0xff));
        return s.toString();
    }

    static String preview(byte[] b) {
        var s = new StringBuilder();
        for (var i = 0; i < Math.min(b.length, 200); i++) {
            var ch = b[i] & 0xff;
            s.append(ch >= 32 && ch < 127 ? (char) ch : '.');
        }
        return s.toString();
    }
}
