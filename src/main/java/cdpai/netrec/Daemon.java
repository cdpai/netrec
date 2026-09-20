package cdpai.netrec;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.util.*;

/** Client-side daemon lifecycle: probe, auto-start (detached), graceful stop. */
public final class Daemon {
    static final String IDLE_MINUTES = "480";

    private Daemon() {}

    public static boolean disabled() { return "1".equals(System.getenv("NETREC_NO_AUTOSTART")); }

    /** null when no daemon is listening. */
    public static JsonNode ping() {
        try { return Api.post("/ping", null); } catch (Exception e) { return null; }
    }

    public static void spawn() {
        try {
            Files.createDirectories(J.home());
            var cmd = command();
            System.err.println("[netrec] no daemon -- starting one (log: " + J.daemonLog() + ")");
            new ProcessBuilder(cmd).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(J.daemonLog().toFile()))
                .directory(J.home().toFile()).start();
            waitUp(90_000);
        } catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException("could not start the daemon: " + e.getMessage()); }
    }

    /** Prefer re-launching our own exe (keeps the jr AOT cache); fall back to java -jar / -cp. */
    static List<String> command() throws Exception {
        var self = ProcessHandle.current().info().command().orElse("");
        if (self.toLowerCase().endsWith("netrec.exe")) return List.of(self, "serve", "--idle", IDLE_MINUTES);
        var code = Path.of(Netrec.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var win = System.getProperty("os.name").toLowerCase().contains("win");
        var java = Path.of(System.getProperty("java.home"), "bin", win ? "javaw.exe" : "java").toString();
        return Files.isDirectory(code)
            ? List.of(java, "-cp", code.toString(), "cdpai.netrec.Netrec", "serve", "--idle", IDLE_MINUTES)
            : List.of(java, "-jar", code.toString(), "serve", "--idle", IDLE_MINUTES);
    }

    static void waitUp(long ms) {
        var end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (ping() != null) return;
            try { Thread.sleep(250); } catch (InterruptedException e) { return; }
        }
        throw new IllegalStateException("the daemon did not come up in time -- see " + J.daemonLog());
    }

    /** Graceful stop: the daemon closes the DB properly, so the next start has no recovery to do. */
    public static boolean stop() {
        if (ping() == null) return false;
        try { Api.post("/shutdown", null); } catch (Exception ignore) {}
        var end = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < end) {
            if (ping() == null) return true;
            try { Thread.sleep(200); } catch (InterruptedException e) { return true; }
        }
        throw new IllegalStateException("the daemon is still up after 20s -- kill it manually");
    }
}
