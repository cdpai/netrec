package cdpai.netrec.cmd;

import cdpai.netrec.*;
import picocli.CommandLine.*;
import java.util.concurrent.Callable;

@Command(name = "stop", description = "Detach a recording session (the daemon and the data stay).")
public final class RecStopCmd implements Callable<Integer> {
    @Option(names = { "-s", "--session" }, description = "Session to stop. Default: the only one recording.") String session;
    @Option(names = "--all", description = "Stop every recording session.") boolean all;

    public Integer call() {
        var body = J.obj().put("all", all);
        if (session != null) body.put("session", session);
        var r = Api.call("/rec/stop", body, false);
        for (var s : r.path("stopped"))
            System.out.println("stopped  session=" + s.path("session").asText() + "  records=" + s.path("records").asInt());
        return 0;
    }
}
