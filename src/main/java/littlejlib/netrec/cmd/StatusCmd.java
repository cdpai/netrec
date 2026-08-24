package littlejlib.netrec.cmd;

import littlejlib.netrec.*;
import picocli.CommandLine.*;
import java.util.concurrent.Callable;

@Command(name = "status", description = "Is the daemon up, is anything being recorded, how much is stored. "
    + "Never starts a daemon.")
public final class StatusCmd implements Callable<Integer> {
    @Option(names = "--json") boolean json;

    public Integer call() {
        var p = Daemon.ping();
        if (p == null) {
            if (json) { System.out.println(J.pretty(J.obj().put("daemon", "down"))); return 1; }
            System.out.println("daemon   : down   (any netrec command starts one; or run: netrec serve)");
            return 1;
        }
        var rec = Api.call("/rec/status", J.obj(), false);
        if (json) { System.out.println(J.pretty(((com.fasterxml.jackson.databind.node.ObjectNode) p).set("rec", rec))); return 0; }
        System.out.println("daemon   : up  pid=" + p.path("pid").asLong() + "  up " + J.duration(p.path("uptimeMs").asLong())
            + "  ctl=:" + p.path("ctlPort").asInt() + "  hub=:" + p.path("hubPort").asInt()
            + "  v" + p.path("version").asText());
        var sessions = rec.path("sessions");
        if (sessions.size() == 0) System.out.println("recording: nothing attached");
        for (var s : sessions)
            System.out.println("recording: " + s.path("session").asText() + "  " + s.path("state").asText()
                + "  " + s.path("targets").size() + " target(s)  " + s.path("records").asInt() + " records");
        System.out.println("stored   : " + p.path("records").asLong() + " records   " + p.path("db").asText());
        return 0;
    }
}
