package littlejlib.netrec.cmd;

import littlejlib.netrec.*;
import picocli.CommandLine.*;
import java.util.concurrent.Callable;

@Command(name = "tabs", description = "List attachable Chrome/Vivaldi debug targets (pick one for `rec --tab`).")
public final class TabsCmd implements Callable<Integer> {
    @Option(names = "--port", description = "CDP debug port. Default: the per-install port from `netrec config`.") Integer port;
    @Option(names = "--json", description = "Output JSON instead of a table.") boolean json;

    public Integer call() {
        var tabs = Cdp.tabs(port != null ? port : Settings.load().cdpPort());
        if (json) { System.out.println(J.pretty(tabs)); return 0; }
        var i = 0;
        for (var t : tabs) {
            if (!t.path("url").asText().startsWith("http")) continue;
            System.out.printf("%2d  %-6s  %s%n    %s%n", i++, t.path("type").asText(),
                t.path("title").asText(), t.path("url").asText());
        }
        if (i == 0) System.err.println("No http page targets. Open a normal web page in the debug browser.");
        return 0;
    }
}
