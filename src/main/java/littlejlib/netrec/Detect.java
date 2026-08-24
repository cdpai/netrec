package littlejlib.netrec;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Finds a browser that is already listening for CDP, so the debug port never has to be guessed. */
public final class Detect {
    private Detect() {}

    public static List<ObjectNode> scan(int configured) {
        var ports = new TreeSet<Integer>(List.of(configured, 9222, 9223, 9229));
        ports.addAll(listening());
        ports.remove(J.CTL_PORT);
        var hub = J.portRange(Settings.load().hubPort());
        ports.removeIf(p -> p >= hub[0] && p <= hub[1]);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = ports.stream().map(p -> pool.submit(() -> probe(p))).toList();
            var out = new ArrayList<ObjectNode>();
            for (var f : futures) {
                try { var r = f.get(); if (r != null) out.add(r); } catch (Exception ignore) {}
            }
            return out;
        }
    }

    static ObjectNode probe(int port) {
        try {
            var v = J.OM.readTree(get("http://127.0.0.1:" + port + "/json/version", 500));
            if (!v.path("webSocketDebuggerUrl").isTextual()) return null;
            var pages = 0;
            try { pages = Cdp.pages(J.OM.readTree(get("http://127.0.0.1:" + port + "/json/list", 1500))).size(); }
            catch (Exception ignore) {}
            return J.obj().put("port", port).put("browser", v.path("Browser").asText(""))
                .put("pages", pages);
        } catch (Exception e) { return null; }
    }

    static Set<Integer> listening() {
        var out = new TreeSet<Integer>();
        try {
            var proc = new ProcessBuilder("netstat", "-ano", "-p", "tcp").redirectErrorStream(true).start();
            try (var r = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (!line.contains("LISTENING")) continue;
                    var parts = line.trim().split("\\s+");
                    if (parts.length < 2) continue;
                    var i = parts[1].lastIndexOf(':');
                    if (i < 0) continue;
                    try { out.add(Integer.parseInt(parts[1].substring(i + 1))); } catch (NumberFormatException ignore) {}
                }
            }
            proc.waitFor(5, TimeUnit.SECONDS);
        } catch (Exception ignore) {}
        out.removeIf(p -> p < 1024 || p > 65535);
        return out;
    }

    static String get(String url, int timeoutMs) throws Exception {
        var c = (HttpURLConnection) new URI(url).toURL().openConnection();
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        try (var is = c.getInputStream()) { return new String(is.readAllBytes(), StandardCharsets.UTF_8); }
    }
}
