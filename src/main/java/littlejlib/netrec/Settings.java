package littlejlib.netrec;

import java.io.IOException;
import java.nio.file.*;
import java.util.Properties;
import java.util.concurrent.ThreadLocalRandom;

/** Persisted settings at ~/littlejlib/netrec/config.properties. CDP port is randomized per-install (not 9222). */
public final class Settings {
    static final String CDP_PORT = "cdp.port", HUB_PORT = "hub.port";
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
