package littlejlib.netrec.cmd;

import littlejlib.netrec.*;
import picocli.CommandLine.*;
import java.util.concurrent.Callable;

@Command(name = "marks", description = "List timeline marks, newest first.")
public final class MarksCmd implements Callable<Integer> {
    @Option(names = { "-n", "--limit" }, defaultValue = "20") int limit;
    @Option(names = "--json") boolean json;

    public Integer call() {
        var rows = Api.query("select ts,label,session from Mark order by ts desc limit " + limit, null);
        if (json) { System.out.println(J.pretty(rows)); return 0; }
        if (rows.size() == 0) { System.err.println("no marks yet -- run: netrec mark \"about to save\""); return 0; }
        for (var r : rows)
            System.out.printf("%s  %-24s %s%n", J.iso(r.path("ts").asLong()),
                r.path("session").asText(), r.path("label").asText());
        return 0;
    }
}
