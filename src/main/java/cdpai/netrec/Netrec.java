package cdpai.netrec;

import cdpai.netrec.cmd.*;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import java.util.logging.Level;
import java.util.logging.Logger;

@Command(name = "netrec", mixinStandardHelpOptions = true, version = "netrec " + J.VERSION,
    subcommands = { ConfigCmd.class, TabsCmd.class, RecCmd.class, ServeCmd.class, StatusCmd.class, StopCmd.class,
        LsCmd.class, ShowCmd.class, CurlCmd.class, CookiesCmd.class, SessionsCmd.class, MarkCmd.class,
        MarksCmd.class, ClearCmd.class },
    description = "Record browser network traffic via the Chrome DevTools Protocol into a local ArcadeDB hub, "
        + "and expose it to the CLI / an AI agent so it can inspect what happened and synthesize replay code. "
        + "Architecture: one persistent daemon holds the DB and owns the recorder; every command (rec included) "
        + "is a thin, fast client of its localhost ctl API, and starts the daemon if none is running.",
    footerHeading = "%nQuickstart (through cdpgate, which owns the browser; there is no debug port -- run `netrec config`):%n",
    footer = {
        "  1. cdpg profiles                              which profiles exist (no approval needed)",
        "  2. netrec tabs --profiles Work --domains youtube.com     first use: approve netrec in cdpgate's window",
        "  3. netrec rec --tab <substring> --profiles Work --domains youtube.com   attach and return (recording outlives the command)",
        "  4. netrec mark \"about to save\"                stamp the timeline, then act in the browser",
        "  5. netrec ls --api --since-mark               what the click actually sent",
        "     netrec ls --writes --since 5m --jq '{method,url,req}'      shaped in the daemon, not by jq downstream",
        "     netrec show <id> --req                     request side only (the answer, 9 times out of 10)",
        "     netrec curl <id> --reveal                  ready-to-run replay with real auth",
        "     netrec cookies --url <host>                the jar itself (auth cookie + expiry), no traffic needed; the",
        "                                                whole profile's jar -- --domains limits tabs, not cookies",
        "  6. netrec rec stop        netrec status        netrec stop   (graceful; closes the DB properly)",
        "",
        "Data + settings live in ~/cdpai/netrec/. Captures contain live cookies; output masks secrets",
        "unless --reveal. Capture scope is printed when you attach -- trust nothing it did not claim to see.",
        "Legacy: --direct / --port connect to a --remote-debugging-port browser instead (discouraged)." })
public final class Netrec {
    static {
        System.setProperty("polyglot.engine.WarnInterpreterOnly", "false");
        Logger.getLogger("com.arcadedb.script.GraalPolyglotEngine").setLevel(Level.OFF);
        Logger.getLogger("com.arcadedb").setLevel(Level.WARNING);
    }

    public static void main(String[] args) {
        var cmd = new CommandLine(new Netrec()).setCaseInsensitiveEnumValuesAllowed(true);
        cmd.setExecutionExceptionHandler((ex, c, pr) -> {
            c.getErr().println("error: " + (ex.getMessage() == null ? ex.toString() : ex.getMessage()));
            return 1;
        });
        System.exit(cmd.execute(args));
    }
}
