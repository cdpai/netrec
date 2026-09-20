package cdpai.netrec.cmd;

import cdpai.netrec.*;
import picocli.CommandLine.*;
import java.util.*;
import java.util.concurrent.Callable;

@Command(name = "ls", description = "Select records (JSON by default). With --jq the expression runs inside the "
    + "daemon, so what crosses to stdout is already reduced.")
public final class LsCmd implements Callable<Integer> {
    @Option(names = { "-s", "--session" }) String session;
    @Option(names = "--host", description = "Host substring.") String host;
    @Option(names = { "-m", "--method" }, split = ",", description = "One or more methods, e.g. POST or POST,PATCH.") List<String> method;
    @Option(names = "--status", description = "404, 4xx, or a comma list (200,404).") String status;
    @Option(names = { "-T", "--type" }, split = ",", description = "Resource types, e.g. XHR,Fetch,Document.") List<String> type;
    @Option(names = { "-u", "--url" }, description = "URL substring.") String url;
    @Option(names = "--since", description = "Relative window, e.g. 30s, 5m, 2h, 7d.") String since;
    @Option(names = "--since-mark", arity = "0..1", fallbackValue = "",
        description = "Everything after the newest mark (optionally the newest whose label matches).") String sinceMark;
    @Option(names = "--writes", description = "Shorthand for --method POST,PUT,PATCH,DELETE -- what did that click send?") boolean writes;
    @Option(names = "--api", description = "Shorthand for --type XHR,Fetch -- drops the _next/static and beacon noise.") boolean api;
    @Option(names = { "-n", "--limit" }, defaultValue = "50") int limit;
    @Option(names = "--jq", description = "jq expression per record; JSON bodies are pre-parsed as .req / .resp.") String jq;
    @Option(names = "--fields", description = "Comma list of fields to keep.") String fields;
    @Option(names = "--body", description = "req|resp|both|none -- which body survives projection.") String body;
    @Option(names = "--body-head", defaultValue = "0", description = "Truncate each body to N chars.") int bodyHead;
    @Option(names = "--reveal", description = "Do not mask cookies / auth in the output.") boolean reveal;
    @Option(names = "--table", description = "Human table instead of JSON.") boolean table;
    @Option(names = "--json", description = "Force JSON output (the default).") boolean json;

    static final List<String> WRITE_METHODS = List.of("POST", "PUT", "PATCH", "DELETE");
    static final String COLUMNS = "id,ts,method,status,host,url,resourceType,state,session";

    public Integer call() {
        var w = new Where();
        if (session != null) w.eq("session", session);
        if (host != null) w.like("host", host);
        if (url != null) w.like("url", url);
        if (status != null) w.status(status);
        var methods = new ArrayList<String>();
        if (method != null) methods.addAll(method);
        if (writes) methods.addAll(WRITE_METHODS);
        if (!methods.isEmpty()) w.in("method", methods.stream().distinct().toList(), true);
        var types = new ArrayList<String>();
        if (type != null) types.addAll(type);
        if (api) types.addAll(List.of("XHR", "Fetch"));
        if (!types.isEmpty()) w.in("resourceType", types.stream().distinct().toList(), false);
        if (since != null) w.gte("ts", System.currentTimeMillis() - J.parseSince(since));
        if (sinceMark != null) w.gte("ts", markTs(sinceMark));

        var wide = jq != null || fields != null || body != null || bodyHead > 0;
        var q = J.obj()
            .put("sql", w.sql(wide ? "*" : COLUMNS, "ReqRec", "ts desc", limit))
            .put("reveal", reveal).put("bodyHead", bodyHead);
        q.set("params", J.OM.valueToTree(w.params()));
        if (jq != null) q.put("jq", jq);
        if (fields != null) q.put("fields", fields);
        if (body != null) q.put("body", body);
        var rows = Api.project(q);

        if (jq != null) { rows.forEach(r -> System.out.println(r.isTextual() ? r.asText() : r.toString())); return 0; }
        if (!table) { System.out.println(J.pretty(rows)); return 0; }
        for (var r : rows)
            System.out.printf("%-14s  %-6s %3s  %-22s %s%n", r.path("id").asText(),
                r.path("method").asText(), r.path("status").asText("-"),
                trim(r.path("host").asText(), 22), trim(r.path("url").asText(), 70));
        return 0;
    }

    long markTs(String label) {
        var w = new Where();
        if (!label.isEmpty()) w.like("label", label);
        var rows = Api.query(w.sql("ts,label", "Mark", "ts desc", 1), w.params());
        if (rows.size() == 0) throw new IllegalStateException("no mark" + (label.isEmpty() ? "" : " matching '" + label + "'")
            + " -- set one with: netrec mark \"about to save\"");
        return rows.get(0).path("ts").asLong();
    }

    static String trim(String s, int n) { return s.length() > n ? s.substring(0, n - 1) + "..." : s; }
}
