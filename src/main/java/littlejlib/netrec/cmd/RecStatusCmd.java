package littlejlib.netrec.cmd;

import littlejlib.netrec.*;
import picocli.CommandLine.*;
import java.util.concurrent.Callable;

@Command(name = "status", description = "What is being recorded right now, and how much has landed.")
public final class RecStatusCmd implements Callable<Integer> {
    @Option(names = "--json") boolean json;

    public Integer call() {
        if (Daemon.ping() == null) {
            System.out.println("not recording  (no daemon)");
            return 1;
        }
        var r = Api.call("/rec/status", J.obj(), false);
        if (json) { System.out.println(J.pretty(r)); return 0; }
        var sessions = r.path("sessions");
        if (sessions.size() == 0) {
            System.out.println("not recording  (daemon up, nothing attached)");
            return 1;
        }
        System.out.println("cdp port " + r.path("cdpPort").asInt() + "  connected=" + r.path("connected").asBoolean());
        for (var s : sessions) {
            System.out.println(String.format("%-10s %-20s %6d records  since %s", s.path("state").asText(),
                s.path("session").asText(), s.path("records").asInt(), J.iso(s.path("since").asLong())));
            for (var t : s.path("targets"))
                System.out.println("    " + String.format("%-14s", t.path("type").asText()) + t.path("url").asText());
            if (s.hasNonNull("error")) System.out.println("    error: " + s.path("error").asText());
        }
        return 0;
    }
}
