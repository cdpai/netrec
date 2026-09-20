package cdpai.netrec;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.*;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** The daemon's localhost control+query API. Every netrec command is a client of this. */
public final class Ctl {
    final HttpServer http;
    final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        var t = new Thread(r, "netrec-idle"); t.setDaemon(true); return t;
    });
    volatile long touched = System.currentTimeMillis();

    public Ctl() throws IOException {
        try { http = HttpServer.create(new InetSocketAddress("127.0.0.1", J.CTL_PORT), 0); }
        catch (BindException e) {
            throw new IllegalStateException(Daemon.ping() != null
                ? "a netrec daemon already holds :" + J.CTL_PORT + "  (netrec status  /  netrec stop)"
                : "the ctl port :" + J.CTL_PORT + " is held by a process that is not netrec -- free that port"
                    + "  (every netrec command reaches the daemon there, so it is not optional)");
        }
        http.setExecutor(Executors.newFixedThreadPool(4));
    }

    public Ctl route(String path, Route r) {
        http.createContext(path, ex -> {
            touched = System.currentTimeMillis();
            try {
                var out = r.handle(read(ex));
                send(ex, 200, out == null ? J.obj() : out);
            } catch (Exception e) {
                var m = e.getMessage();
                send(ex, 400, J.obj().put("error", m == null ? e.toString() : m));
            } finally { ex.close(); }
        });
        return this;
    }

    public void start() { http.start(); }

    public void stopServer() { http.stop(0); }

    /** Shut the daemon down after `minutes` with no ctl traffic and nothing recording (0 = never). */
    public void idleShutdown(int minutes, BooleanSupplier busy, Runnable shutdown) {
        if (minutes <= 0) return;
        var ms = minutes * 60_000L;
        timer.scheduleAtFixedRate(() -> {
            if (!busy.getAsBoolean() && System.currentTimeMillis() - touched > ms) {
                System.err.println("[netrec] idle " + minutes + "m -- shutting down");
                shutdown.run();
            }
        }, 60, 60, TimeUnit.SECONDS);
    }

    static JsonNode read(HttpExchange ex) throws IOException {
        var b = ex.getRequestBody().readAllBytes();
        if (b.length == 0) return J.obj();
        var n = J.OM.readTree(b);
        return n == null || n.isNull() ? J.obj() : n;
    }

    static void send(HttpExchange ex, int code, JsonNode body) throws IOException {
        var b = body.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, b.length);
        try (var os = ex.getResponseBody()) { os.write(b); }
    }
}
