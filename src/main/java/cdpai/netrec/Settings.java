package cdpai.netrec;

import java.io.IOException;
import java.nio.file.*;
import java.util.Properties;
import java.util.concurrent.ThreadLocalRandom;

/** Persisted settings at ~/cdpai/netrec/config.properties. CDP port is randomized per-install (not 9222). */
public final class Settings {
    static final String CDP_PORT = "cdp.port", HUB_PORT = "hub.port";
    static final String CDP_MODE = "cdp.mode", GATE_PIPE = "gate.pipe", GATE_KEY = "gate.key";
    /** The default and preferred mode -- direct-port is the discouraged legacy fallback. */
    public static final String MODE_GATE = "gate", MODE_DIRECT = "direct";
    final Path file = J.home().resolve("config.properties");
    final Properties p = new Properties();

    public static Settings load() {
        var s = new Settings();
        s.read();
        return s;
    }

    void read() {
        try {
            if (Files.exists(file)) try (var in = Files.newInputStream(file)) { p.load(in); }
            if (p.getProperty(CDP_PORT) == null) {
                p.setProperty(CDP_PORT, String.valueOf(ThreadLocalRandom.current().nextInt(20000, 45000)));
                save();
            }
        } catch (IOException e) { throw new RuntimeException(e); }
    }

    public int cdpPort() { return Integer.parseInt(p.getProperty(CDP_PORT)); }
    public void cdpPort(int port) { p.setProperty(CDP_PORT, String.valueOf(port)); }

    /** "gate" (default, preferred) or "direct" (legacy fallback: a raw debug-port websocket). */
    public String cdpMode() { return p.getProperty(CDP_MODE, MODE_GATE); }
    public void cdpMode(String mode) {
        if (!MODE_GATE.equals(mode) && !MODE_DIRECT.equals(mode))
            throw new IllegalArgumentException("mode must be '" + MODE_GATE + "' or '" + MODE_DIRECT + "', got: " + mode);
        p.setProperty(CDP_MODE, mode);
    }

    /** cdpgate's named pipe. Default matches cdpai-gate-client's own well-known name. */
    public String gatePipe() { return p.getProperty(GATE_PIPE, cdpai.gate.client.GateClient.PIPE_NAME); }
    public void gatePipe(String pipe) { p.setProperty(GATE_PIPE, pipe); }

    /**
     * The name of netrec's keyed identity (~/cdpai/gate/keys/<name>.json), for when the daemon is started at logon with no meaningful
     * parent process for cdpgate to attest (see the gate design doc). Null means attested mode --
     * the ordinary case for a daemon started interactively, where cdpgate identifies netrec from
     * the kernel and prompts a human the first time.
     */
    public String gateKey() { var t = p.getProperty(GATE_KEY); return t == null || t.isBlank() ? null : t; }
    public void gateKey(String name) {
        if (name == null || name.isBlank()) p.remove(GATE_KEY);
        else p.setProperty(GATE_KEY, name);
    }

    /** Hub HTTP port spec, resolved: env NETREC_HUB_PORT, then hub.port, then the default range. */
    public String hubPort() {
        var env = System.getenv("NETREC_HUB_PORT");
        if (env != null && !env.isBlank()) return env.trim();
        return p.getProperty(HUB_PORT, J.HUB_PORT_DEFAULT);
    }

    public void hubPort(String spec) {
        J.portRange(spec);
        p.setProperty(HUB_PORT, spec.trim());
    }

    public void save() {
        try {
            Files.createDirectories(file.getParent());
            try (var out = Files.newOutputStream(file)) { p.store(out, "netrec settings"); }
        } catch (IOException e) { throw new RuntimeException(e); }
    }
}
