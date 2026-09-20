package cdpai.netrec.cmd;

import cdpai.netrec.*;
import picocli.CommandLine.*;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "show", description = "Show one record. Projection happens in the daemon: --req / --body / "
    + "--body-head / --fields / --jq all shrink it before it is written to stdout.")
public final class ShowCmd implements Callable<Integer> {
    @Parameters(index = "0", description = "Record id (from `ls`).") String id;
    @Option(names = "--reveal", description = "Show cookies / auth headers unmasked (needed for true replay).") boolean reveal;
    @Option(names = "--req", description = "Request side only -- usually the answer when reverse-engineering a write.") boolean req;
    @Option(names = "--resp", description = "Response side only.") boolean resp;
    @Option(names = "--body", description = "req|resp|both|none (explicit form of --req / --resp).") String body;
    @Option(names = "--body-head", defaultValue = "0", description = "Truncate each body to N chars.") int bodyHead;
    @Option(names = "--parsed", description = "Also expose JSON bodies parsed, as .req / .resp.") boolean parsed;
    @Option(names = "--fields", description = "Comma list of fields to keep.") String fields;
    @Option(names = "--jq", description = "jq expression, e.g. '.resp.results | length'.") String jq;

    public Integer call() {
        var mode = body != null ? body : req && !resp ? "req" : resp && !req ? "resp" : null;
        var q = J.obj().put("sql", "select from ReqRec where id = :id limit 1").put("reveal", reveal)
            .put("bodyHead", bodyHead).put("parse", parsed || jq != null);
        q.set("params", J.OM.valueToTree(Map.of("id", id)));
        if (mode != null) q.put("body", mode);
        if (fields != null) q.put("fields", fields);
        if (jq != null) q.put("jq", jq);
        var rows = Api.project(q);
        if (rows.size() == 0) { System.err.println("not found: " + id); return 1; }
        if (jq != null) rows.forEach(r -> System.out.println(r.isTextual() ? r.asText() : r.toString()));
        else System.out.println(J.pretty(rows.get(0)));
        return 0;
    }
}
