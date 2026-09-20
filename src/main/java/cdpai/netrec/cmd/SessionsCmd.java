package cdpai.netrec.cmd;

import cdpai.netrec.*;
import picocli.CommandLine.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Callable;

@Command(name = "sessions", description = "List recording sessions with counts and time span.")
public final class SessionsCmd implements Callable<Integer> {
    @Option(names = "--json") boolean json;

    public Integer call() {
        var rows = Api.query("select session, count(*) as n, min(ts) as first, max(ts) as last "
            + "from ReqRec group by session order by last desc", null);
        if (json) { System.out.println(J.pretty(rows)); return 0; }
        for (var r : rows)
            System.out.printf("%-24s  %5d req   %s .. %s%n", r.path("session").asText(),
                r.path("n").asInt(), iso(r.path("first").asLong()), iso(r.path("last").asLong()));
        if (rows.size() == 0) System.err.println("no sessions yet -- run: netrec rec");
        return 0;
    }

    static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    static String iso(long ms) {
        return ms == 0 ? "-" : LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault()).format(FMT);
    }
}
