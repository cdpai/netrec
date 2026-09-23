package cdpai.netrec.cmd;

import cdpai.netrec.*;
import picocli.CommandLine.*;
import java.util.concurrent.Callable;

@Command(name = "config", description = "Show settings + how to connect. Run this FIRST. "
    + "--detect finds a legacy direct-port browser that is already listening.")
public final class ConfigCmd implements Callable<Integer> {
    @Option(names = "--mode", description = "Set the connection mode: 'gate' (default, preferred -- through "
        + "cdpgate) or 'direct' (legacy fallback -- an unauthenticated debug-port websocket).") String mode;
    @Option(names = "--gate-pipe", description = "Set cdpgate's named pipe. Default matches cdpgate's own well-known name.") String gatePipe;
    @Option(names = "--gate-key", description = "Use a keyed identity for a daemon started at logon, whose process tree "
        + "means nothing: the NAME of a key under ~/cdpai/gate/keys/, created on first use. cdpgate asks a human once;"
        + " scoped by profiles and domains the approval can last 30 days. Empty string: back to attested mode.") String gateKey;
    @Option(names = "--cdp-port", description = "Set the direct-mode (legacy) CDP debug port.") Integer cdpPort;
    @Option(names = "--hub-port", paramLabel = "<port|range>",
        description = "Set the hub's (ArcadeDB) HTTP port or range, e.g. 2600 or 2600-2609. Takes effect the next "
            + "time the daemon starts. Default " + J.HUB_PORT_DEFAULT + "; the first free port in the range wins.") String hubPort;
    @Option(names = "--detect", description = "Probe localhost for a live direct-mode CDP endpoint (legacy fallback discovery).") boolean detect;
    @Option(names = "--adopt", description = "With --detect: save the port that was found, and switch mode to direct.") boolean adopt;

    public Integer call() {
        var s = Settings.load();
        if (mode != null) { s.cdpMode(mode); s.save(); System.err.println("set cdp.mode=" + mode); }
        if (gatePipe != null) { s.gatePipe(gatePipe); s.save(); System.err.println("set gate.pipe=" + gatePipe); }
        if (gateKey != null) { s.gateKey(gateKey); s.save();
            System.err.println(gateKey.isBlank() ? "cleared gate.key (back to attested mode)" : "set gate.key=" + gateKey); }
        if (cdpPort != null) { s.cdpPort(cdpPort); s.save(); System.err.println("set cdp.port=" + cdpPort); }
        if (hubPort != null) { s.hubPort(hubPort); s.save(); System.err.println("set hub.port=" + hubPort
            + " (restart the daemon to apply:  netrec stop)"); }
        if (detect) return detect(s);
        var up = Daemon.ping();
        System.out.println("data dir : " + J.home());
        System.out.println("mode     : " + s.cdpMode()
            + (Settings.MODE_GATE.equals(s.cdpMode()) ? "   (preferred -- through cdpgate, no open port)"
                : "   (LEGACY FALLBACK -- unauthenticated debug-port websocket; prefer --mode gate)"));
        if (Settings.MODE_GATE.equals(s.cdpMode())) {
            System.out.println("gate pipe: " + s.gatePipe());
            System.out.println("gate auth: " + (s.gateKey() == null
                ? "attested (kernel-identified; cdpgate prompts a human the first time)"
                : "keyed, identity \"" + s.gateKey() + "\" (~/cdpai/gate/keys/" + s.gateKey() + ".json)"));
        } else {
            System.out.println("cdp port : " + s.cdpPort() + "   (per-install, deliberately NOT the well-known 9222)");
        }
        System.out.println("ctl api  : http://127.0.0.1:" + J.CTL_PORT + "   (localhost; every command talks to this)");
        System.out.println("hub api  : " + (up == null
            ? "range " + s.hubPort() + "   (ArcadeDB; first free port in the range is bound when the daemon starts)"
            : "http://127.0.0.1:" + up.path("hubPort").asInt() + "   (ArcadeDB, localhost; direct SQL escape hatch)"));
        System.out.println("daemon   : " + (up == null ? "down (starts on demand)" : "up"));
        System.out.println();
        if (Settings.MODE_GATE.equals(s.cdpMode())) {
            System.out.println("1. Make sure cdpgate is running (it owns Vivaldi in --remote-debugging-pipe mode)");
            System.out.println("2. netrec tabs                       # first use: approve netrec in cdpgate's popup");
        } else {
            System.out.println("1. Launch the browser with this port (or find a running one:  netrec config --detect --adopt):");
            System.out.println("     vivaldi.exe --remote-debugging-port=" + s.cdpPort());
            System.out.println("     chrome.exe  --remote-debugging-port=" + s.cdpPort());
            System.out.println("2. netrec tabs                       # confirm the tab is attachable");
        }
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
            s.cdpMode(Settings.MODE_DIRECT);
            s.save();
            System.out.println("adopted cdp.port=" + pick + ", cdp.mode=direct");
        } else if (pick != s.cdpPort())
            System.out.println("adopt it with:  netrec config --cdp-port " + pick + "   (or: netrec config --detect --adopt)");
        return 0;
    }
}
