package cdpai.netrec.cmd;

import cdpai.netrec.*;
import picocli.CommandLine.*;
import java.util.concurrent.Callable;

@Command(name = "config", description = "Show settings + the exact browser launch command. Run this FIRST. "
    + "--detect finds a browser that is already listening.")
public final class ConfigCmd implements Callable<Integer> {
    @Option(names = "--cdp-port", description = "Set the CDP debug port netrec connects to.") Integer cdpPort;
    @Option(names = "--hub-port", paramLabel = "<port|range>",
        description = "Set the hub's (ArcadeDB) HTTP port or range, e.g. 2600 or 2600-2609. Takes effect the next "
            + "time the daemon starts. Default " + J.HUB_PORT_DEFAULT + "; the first free port in the range wins.") String hubPort;
    @Option(names = "--detect", description = "Probe localhost for a live CDP endpoint (kills the 'which port?' guessing).") boolean detect;
    @Option(names = "--adopt", description = "With --detect: save the port that was found.") boolean adopt;

    public Integer call() {
        var s = Settings.load();
        if (cdpPort != null) { s.cdpPort(cdpPort); s.save(); System.err.println("set cdp.port=" + cdpPort); }
        if (hubPort != null) { s.hubPort(hubPort); s.save(); System.err.println("set hub.port=" + hubPort
            + " (restart the daemon to apply:  netrec stop)"); }
        if (detect) return detect(s);
        var port = s.cdpPort();
        var up = Daemon.ping();
        System.out.println("data dir : " + J.home());
        System.out.println("cdp port : " + port + "   (per-install, deliberately NOT the well-known 9222)");
        System.out.println("ctl api  : http://127.0.0.1:" + J.CTL_PORT + "   (localhost; every command talks to this)");
        System.out.println("hub api  : " + (up == null
            ? "range " + s.hubPort() + "   (ArcadeDB; first free port in the range is bound when the daemon starts)"
            : "http://127.0.0.1:" + up.path("hubPort").asInt() + "   (ArcadeDB, localhost; direct SQL escape hatch)"));
        System.out.println("daemon   : " + (up == null ? "down (starts on demand)" : "up"));
        System.out.println();
        System.out.println("1. Launch the browser with this port (or find a running one:  netrec config --detect --adopt):");
        System.out.println("     vivaldi.exe --remote-debugging-port=" + port);
        System.out.println("     chrome.exe  --remote-debugging-port=" + port);
        System.out.println("2. netrec tabs                       # confirm the tab is attachable");
        System.out.println("3. netrec rec --tab <substring>      # returns at once; recording lives in the daemon");
        System.out.println("4. netrec mark \"about to save\"       # then do the thing in the browser");
        System.out.println("5. netrec ls --api --since-mark  ->  netrec show <id> --req  ->  netrec curl <id> --reveal");
        return 0;
    }

    Integer detect(Settings s) {
        var found = Detect.scan(s.cdpPort());
        if (found.isEmpty()) {
            System.out.println("no CDP endpoint is listening on localhost.");
            System.out.println("start one:  vivaldi.exe --remote-debugging-port=" + s.cdpPort());
            return 1;
        }
        for (var f : found)
            System.out.println("found  :" + f.path("port").asInt() + "   " + f.path("browser").asText()
                + "   " + f.path("pages").asInt() + " http tab(s)"
                + (f.path("port").asInt() == s.cdpPort() ? "   <- the configured port" : ""));
        var pick = found.get(0).path("port").asInt();
        if (adopt) {
            s.cdpPort(pick);
            s.save();
            System.out.println("adopted cdp.port=" + pick);
        } else if (pick != s.cdpPort())
            System.out.println("adopt it with:  netrec config --cdp-port " + pick + "   (or: netrec config --detect --adopt)");
        return 0;
    }
}
