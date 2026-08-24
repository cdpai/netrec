package littlejlib.netrec;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Handlers behind the ctl API: query+projection, recorder control, marks, lifecycle. */
public final class CtlApi {
    final Hub hub;
    final Ctl ctl;
    final Store store;
    final RecMgr rec;
    final long started = System.currentTimeMillis();

    public CtlApi(Hub hub, Ctl ctl) {
        this.hub = hub; this.ctl = ctl;
        this.store = new Store(hub.db());
        this.rec = new RecMgr(store);
    }

    public void wire(int idleMinutes) {
        ctl.route("/ping", r -> ping())
           .route("/q", this::q)
           .route("/rec/start", this::recStart)
           .route("/rec/stop", this::recStop)
           .route("/rec/status", r -> rec.status())
           .route("/cookies", this::cookies)
           .route("/mark", this::mark)
           .route("/clear", this::clear)
           .route("/shutdown", r -> shutdown());
        ctl.idleShutdown(idleMinutes, rec::busy, this::halt);
        ctl.start();
    }

    static HashMap<String, Object> params(JsonNode in) {
        var params = new HashMap<String, Object>();
        in.path("params").fields().forEachRemaining(e -> params.put(e.getKey(), Store.value(e.getValue())));
        return params;
    }

    /** Detach recorders and flush anything in flight; used by the Ctrl-C / kill shutdown hook. */
    public void closeQuietly() { try { rec.stopAll(); } catch (Exception ignore) {} }

    JsonNode ping() {
        return J.obj().put("ok", true).put("version", J.VERSION)
            .put("pid", ProcessHandle.current().pid())
            .put("uptimeMs", System.currentTimeMillis() - started)
            .put("recording", rec.busy())
            .put("ctlPort", J.CTL_PORT).put("hubPort", hub.httpPort())
            .put("db", J.dbDir().resolve(J.DB_NAME).toString())
            .put("records", store.count("ReqRec"));
    }

    JsonNode q(JsonNode in) {
        var sql = J.str(in, "sql");
        if (sql == null) throw new IllegalArgumentException("sql required");
        var params = params(in);
        var jq = J.str(in, "jq");
        var fields = J.str(in, "fields");
        var raw = J.b(in, "raw", false);
        var reveal = J.b(in, "reveal", false);
        var parse = J.b(in, "parse", jq != null);
        var out = J.OM.createArrayNode();
        for (var r : store.query(sql, params)) {
            var n = raw ? (reveal ? r : Project.maskRaw(r)) : Project.enrich(r, reveal, parse);
            n = Project.body(n, J.str(in, "body"), J.i(in, "bodyHead", 0));
            if (jq != null) Jq.apply(jq, n).forEach(out::add);
            else if (fields != null) out.add(Project.fields(n, List.of(fields.split(","))));
            else out.add(n);
        }
        return J.obj().set("out", out);
    }

    JsonNode recStart(JsonNode in) throws Exception {
        return rec.start(J.str(in, "session"), J.str(in, "tab"), J.b(in, "all", false),
            J.b(in, "children", true), J.i(in, "port", Settings.load().cdpPort()),
            J.i(in, "maxBody", 2_000_000), J.l(in, "waitMs", 0));
    }

    JsonNode cookies(JsonNode in) {
        return rec.cookies(J.i(in, "port", Settings.load().cdpPort()), J.str(in, "url"),
            J.str(in, "domain"), J.str(in, "name"), J.b(in, "reveal", false));
    }

    JsonNode recStop(JsonNode in) {
        return rec.stop(J.str(in, "session"), J.b(in, "all", false));
    }

    JsonNode mark(JsonNode in) {
        var label = J.str(in, "label");
        if (label == null) throw new IllegalArgumentException("label required");
        var session = J.str(in, "session");
        if (session == null) session = rec.currentSession();
        var n = J.obj().put("ts", System.currentTimeMillis()).put("label", label)
            .put("session", session == null ? "" : session);
        store.insert("Mark", n);
        return n;
    }

    /** Dry run unless apply. `all` drops the database files outright -- the only real scrub. */
    JsonNode clear(JsonNode in) {
        var apply = J.b(in, "apply", false);
        var session = J.str(in, "session");
        if (session != null && rec.recording(session))
            throw new IllegalStateException("session '" + session + "' is recording -- netrec rec stop -s " + session + " first");
        var out = J.obj().put("applied", apply);
        if (J.b(in, "all", false)) {
            if (rec.busy()) throw new IllegalStateException("something is still recording -- netrec rec stop --all first");
            out.put("mode", "wipe").put("records", store.count("ReqRec")).put("marks", store.count("Mark"));
            if (apply) {
                var wipedAt = System.currentTimeMillis();
                store.rebind(hub.recreate());
                var stale = hub.staleBytes(wipedAt);
                out.put("scrubbed", stale == 0).put("staleBytes", stale);
            }
            return out;
        }
        var where = J.str(in, "where");
        if (where == null) throw new IllegalArgumentException("a selector is required: --session / --older-than / --host / --all");
        var params = params(in);
        out.put("mode", "delete").put("records", store.count("ReqRec", where, params));
        var marksWhere = J.str(in, "marksWhere");
        if (marksWhere != null) out.put("marks", store.count("Mark", marksWhere, params));
        if (apply) {
            out.put("deletedRecords", store.delete("ReqRec", where, params));
            if (marksWhere != null) out.put("deletedMarks", store.delete("Mark", marksWhere, params));
        }
        return out;
    }

    JsonNode shutdown() {
        new Thread(this::halt, "netrec-shutdown").start();
        return J.obj().put("stopping", true).put("pid", ProcessHandle.current().pid());
    }

    void halt() {
        try { Thread.sleep(150); } catch (InterruptedException ignore) {}
        try { rec.stopAll(); } catch (Exception ignore) {}
        try { ctl.stopServer(); } catch (Exception ignore) {}
        try { hub.stop(); } catch (Exception ignore) {}
        System.exit(0);
    }
}
