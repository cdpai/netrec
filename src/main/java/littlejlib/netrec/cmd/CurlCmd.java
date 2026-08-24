package littlejlib.netrec.cmd;

import com.fasterxml.jackson.databind.JsonNode;
import littlejlib.netrec.*;
import picocli.CommandLine.*;
import java.util.*;
import java.util.concurrent.Callable;

@Command(name = "curl", description = "Emit a curl command that replays a recorded request. "
    + "Secrets stay in the daemon unless --reveal.")
public final class CurlCmd implements Callable<Integer> {
    @Parameters(index = "0", description = "Record id (from `ls`).") String id;
    @Option(names = "--reveal", description = "Include real cookies / auth (required for the replay to authenticate).") boolean reveal;

    public Integer call() {
        var q = J.obj().put("sql", "select from ReqRec where id = :id limit 1").put("raw", true).put("reveal", reveal);
        q.set("params", J.OM.valueToTree(Map.of("id", id)));
        var rows = Api.project(q);
        if (rows.size() == 0) { System.err.println("not found: " + id); return 1; }
        var r = rows.get(0);
        var sb = new StringBuilder("curl -X ").append(r.path("method").asText("GET"))
            .append(" '").append(r.path("url").asText()).append("'");
        var headers = new LinkedHashMap<String, String>();
        merge(headers, r, "reqHeaders");
        merge(headers, r, "reqExtraHeaders");
        headers.forEach((k, v) -> {
            if (k.startsWith(":") || k.equalsIgnoreCase("content-length")) return;
            sb.append(" \\\n  -H '").append(k).append(": ").append(esc(v)).append("'");
        });
        if (r.has("reqBody")) sb.append(" \\\n  --data-raw '").append(esc(r.path("reqBody").asText())).append("'");
        System.out.println(sb);
        return 0;
    }

    void merge(Map<String, String> out, JsonNode r, String field) {
        if (!r.has(field)) return;
        var h = J.tryParse(r.path(field).asText());
        if (h != null) h.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asText()));
    }

    static String esc(String s) { return s.replace("'", "'\\''"); }
}
