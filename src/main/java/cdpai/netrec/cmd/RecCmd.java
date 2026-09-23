package cdpai.netrec.cmd;

import cdpai.netrec.*;
import picocli.CommandLine.*;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "rec", subcommands = { RecStopCmd.class, RecStatusCmd.class },
    description = "Attach the daemon's recorder to browser tab(s) and return. Recording outlives this command; "
        + "exits non-zero unless attachment was confirmed.")
public final class RecCmd implements Callable<Integer> {
    @Option(names = { "-t", "--tab" }, description = "Tab to attach: index (see `tabs`) or url/title substring. "
        + "A substring keeps matching -- tabs that open or navigate into it later are adopted too.") String tab;
    @Option(names = "--all", description = "Attach every http page target, including ones opened later.") boolean all;
    @Option(names = { "-s", "--session" }, description = "Session name. Default: rec-<timestamp>.") String session;
    @Option(names = "--direct", description = "Legacy fallback: connect directly to a debug port instead of "
        + "through cdpgate. Discouraged -- an unauthenticated listener any local process can reach. Implied by --port.") boolean direct;
    @Option(names = "--port", description = "Direct-mode CDP debug port (implies --direct). Default in direct mode: "
        + "the per-install port from `netrec config`.") Integer port;
    @Option(names = "--max-body", defaultValue = "2000000", description = "Max stored body bytes per side (default 2 MB).") int maxBody;
    @Option(names = "--wait", defaultValue = "0", description = "Seconds to sit until a matching tab appears (default 0 = fail fast).") long wait;
    @Option(names = "--children", negatable = true, defaultValue = "true",
        description = "Auto-attach the page's iframes and workers (default true; --no-children to skip).") boolean children;
    @Option(names = "--domains", split = ",", description = "Gate mode only: scope the cdpgate approval to these "
        + "domains (comma-separated, no wildcards). Omit for all domains (narrower approval window).") List<String> domains;
    @Option(names = "--profiles", split = ",", description = "Gate mode only: scope the cdpgate approval to these "
        + "profiles, by name or folder as `cdpg profiles` lists them (comma-separated). Omit for all profiles, which earns a short approval.")
    List<String> profiles;
    @Option(names = "--minutes", description = "Gate mode only: requested approval duration in minutes -- shown to "
        + "the human approving, not guaranteed.") Integer minutes;

    public Integer call() {
        var body = J.obj().put("all", all).put("children", children).put("maxBody", maxBody).put("waitMs", wait * 1000)
            .put("direct", direct);
        if (tab != null) body.put("tab", tab);
        if (session != null) body.put("session", session);
        if (port != null) body.put("port", port);
        J.putStrList(body, "domains", domains);
        J.putStrList(body, "profiles", profiles);
        if (minutes != null) body.put("minutes", minutes);
        var r = Api.call("/rec/start", body, true);
        var state = r.path("state").asText();
        System.out.println(state + "  session=" + r.path("session").asText() + "  targets=" + r.path("targets").size());
        for (var t : r.path("targets"))
            System.out.println("  " + String.format("%-14s", t.path("type").asText()) + t.path("url").asText());
        if (!children) System.err.println("[netrec] --no-children: iframe / service-worker traffic will NOT be captured");
        if (all || tab != null) System.err.println("[netrec] this is a standing subscription: more targets may attach "
            + "after this command returned -- netrec rec status shows the full set");
        if (!"recording".equals(state)) {
            System.err.println("[netrec] nothing attached yet -- no traffic is being captured. Check: netrec tabs");
            return 3;
        }
        System.err.println("[netrec] stop with: netrec rec stop -s " + r.path("session").asText()
            + "   |   mark the moment with: netrec mark \"about to save\"");
        return 0;
    }
}
