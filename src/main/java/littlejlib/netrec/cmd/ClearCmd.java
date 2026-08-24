package littlejlib.netrec.cmd;

import littlejlib.netrec.*;
import picocli.CommandLine.*;
import java.util.concurrent.Callable;

@Command(name = "clear", description = "Delete recorded requests. Dry run unless --yes. Captures hold live "
    + "cookies and auth tokens, so pruning one you are finished with is hygiene, not housekeeping.")
public final class ClearCmd implements Callable<Integer> {
    @Option(names = { "-s", "--session" }, description = "Delete one session's records.") String session;
    @Option(names = "--older-than", description = "Delete records older than e.g. 30s, 5m, 2h, 7d.") String olderThan;
    @Option(names = "--host", description = "Delete records whose host contains this.") String host;
    @Option(names = "--all", description = "Wipe the whole store: the database files are dropped and recreated "
        + "(the only way stored secrets actually leave the disk).") boolean all;
    @Option(names = "--marks", description = "Also delete the marks the selection covers.") boolean marks;
    @Option(names = "--yes", description = "Actually delete. Without it, nothing is touched.") boolean yes;

    public Integer call() {
        var body = J.obj().put("apply", yes).put("all", all);
        if (!all) {
            var w = new Where();
            var m = new Where();
            if (session != null) { w.eq("session", session); m.eq("session", session); body.put("session", session); }
            if (olderThan != null) {
                var cut = System.currentTimeMillis() - J.parseSince(olderThan);
                w.lt("ts", cut);
                m.lt("ts", cut);
            }
            if (host != null) w.like("host", host);
            if (w.empty()) throw new IllegalArgumentException("a selector is required: --session / --older-than / --host / --all");
            body.put("where", w.where());
            if (marks && !m.empty()) body.put("marksWhere", m.where());
            else if (marks) System.err.println("[netrec] --marks ignored: marks can only be selected by --session or --older-than");
            body.set("params", J.OM.valueToTree(w.params()));
        }
        var r = Api.call("/clear", body, true);
        var n = r.path("records").asLong();
        if (all) {
            if (!yes) {
                System.out.println("would WIPE the store: " + n + " records, " + r.path("marks").asLong()
                    + " marks -- files dropped and recreated, secrets gone for real.");
                System.out.println("add --yes to do it");
                return 0;
            }
            System.out.println("wiped: " + n + " records and " + r.path("marks").asLong()
                + " marks gone; database files recreated");
            if (!r.path("scrubbed").asBoolean(false))
                System.err.println("[netrec] WARNING: " + r.path("staleBytes").asLong()
                    + " bytes of the old database are still on disk -- treat the stored secrets as not yet scrubbed");
            return 0;
        }
        if (!yes) {
            System.out.println("would delete " + n + " records"
                + (r.has("marks") ? " and " + r.path("marks").asLong() + " marks" : "") + "  (add --yes)");
            if (n > 0) System.out.println("note: this is a logical delete -- bytes may sit in the ArcadeDB bucket "
                + "until compaction. For a real scrub use: netrec clear --all --yes");
            return 0;
        }
        System.out.println("deleted " + r.path("deletedRecords").asLong() + " records"
            + (r.has("deletedMarks") ? " and " + r.path("deletedMarks").asLong() + " marks" : ""));
        return 0;
    }
}
