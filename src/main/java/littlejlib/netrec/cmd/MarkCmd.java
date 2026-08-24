package littlejlib.netrec.cmd;

import littlejlib.netrec.*;
import picocli.CommandLine.*;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "mark", description = "Stamp the timeline -- 'the human is about to hit Save'. "
    + "Then query with: netrec ls --since-mark.")
public final class MarkCmd implements Callable<Integer> {
    @Parameters(arity = "1..*", description = "Label for this moment.") List<String> label;
    @Option(names = { "-s", "--session" }, description = "Session to attribute it to. Default: the one recording.") String session;

    public Integer call() {
        var body = J.obj().put("label", String.join(" ", label));
        if (session != null) body.put("session", session);
        var r = Api.call("/mark", body, true);
        System.out.println("mark  " + J.iso(r.path("ts").asLong()) + "  \"" + r.path("label").asText() + "\""
            + (r.path("session").asText().isEmpty() ? "" : "  session=" + r.path("session").asText()));
        return 0;
    }
}
