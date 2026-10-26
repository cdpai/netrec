package cdpai.netrec.cmd;

import cdpai.gate.client.ScopeRequest;
import cdpai.netrec.*;
import picocli.CommandLine.*;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "tabs", description = "List attachable Chrome/Vivaldi debug targets (pick one for `rec --tab`). "
    + "Talks straight to the browser (through cdpgate by default) -- the daemon is not involved.")
public final class TabsCmd implements Callable<Integer> {
    @Option(names = "--direct", description = "Legacy fallback: connect directly to a debug port instead of "
        + "through cdpgate. Discouraged. Implied by --port.") boolean direct;
    @Option(names = "--port", description = "Direct-mode CDP debug port (implies --direct).") Integer port;
    @Option(names = "--json", description = "Output JSON instead of a table.") boolean json;
    @Option(names = "--domains", split = ",", description = "Gate mode only: scope the cdpgate approval to these "
        + "domains (comma-separated, no wildcards). Omit for all domains (narrower approval window). Note: this "
        + "command itself lists every target regardless -- the scope only affects the approval cdpgate grants.")
    List<String> domains;
    @Option(names = "--profiles", split = ",", description = "Gate mode only: scope the cdpgate approval to these "
        + "profiles, by name or folder as `cdpg profiles` lists them (comma-separated). Omit for all profiles, which earns a short approval.")
    List<String> profiles;
    @Option(names = "--extensions", description = "Also list browser-extension targets (service workers, background pages, "
        + "chrome-extension:// pages) with their extension id. In gate mode the cdpgate domain of such a target is the "
        + "extension id: --domains <extension-id>. Indexes here match `rec --extensions --tab <index>`.") boolean extensions;
    @Option(names = "--minutes", description = "Gate mode only: requested approval duration in minutes -- shown to "
        + "the human approving, not guaranteed.") Integer minutes;

    public Integer call() {
        var scope = new ScopeRequest(domains, profiles, minutes);
        var spec = ConnSpec.resolve(Settings.load(), port, direct, scope);
        var tabs = RecMgr.tabsFor(spec);
        if (json) { System.out.println(J.pretty(tabs)); return 0; }
        var i = 0;
        var typeCol = extensions ? "%-15s" : "%-6s";
        for (var t : tabs) {
            if (!Cdp.listed(t, extensions)) continue;
            var id = Cdp.extensionId(t.path("url").asText());
            System.out.printf("%2d  " + typeCol + "  %s%s%n    %s%n", i++, t.path("type").asText(),
                t.path("title").asText(), id.isEmpty() ? "" : "  [extension " + id + "]", t.path("url").asText());
        }
        if (i == 0) System.err.println("No http page targets. Open a normal web page in the debug browser."
            + (extensions ? " No extension targets either: is the extension's service worker running?" : ""));
        return 0;
    }
}
