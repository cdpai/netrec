package littlejlib.netrec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;
import java.io.IOException;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Reader-side client for the daemon's ctl API. No DB engine is loaded here, so commands start fast. */
public final class Api {
    static final String BASE = "http://127.0.0.1:" + J.CTL_PORT;
    static final MediaType JSON = MediaType.parse("application/json");
    static final OkHttpClient C = new OkHttpClient.Builder()
        .readTimeout(180, TimeUnit.SECONDS).callTimeout(0, TimeUnit.MILLISECONDS).build();

    private Api() {}

    /** POST, starting the daemon on demand (ssh-agent style) unless autostart is off. */
    public static JsonNode call(String path, ObjectNode body, boolean autostart) {
        try { return post(path, body); }
        catch (ConnectException e) {
            if (!autostart || Daemon.disabled())
                throw new IllegalStateException("no netrec daemon on :" + J.CTL_PORT + " -- start one:  netrec serve");
            Daemon.spawn();
            try { return post(path, body); }
            catch (IOException e2) { throw new IllegalStateException("daemon unreachable: " + e2.getMessage()); }
        }
        catch (IOException e) { throw new IllegalStateException(String.valueOf(e.getMessage())); }
    }

    static JsonNode post(String path, ObjectNode body) throws IOException {
        var req = new Request.Builder().url(BASE + path)
            .post(RequestBody.create((body == null ? J.obj() : body).toString().getBytes(StandardCharsets.UTF_8), JSON))
            .build();
        try (var resp = C.newCall(req).execute()) {
            var s = resp.body().string();
            var n = J.OM.readTree(s);
            if (!resp.isSuccessful()) throw new IllegalStateException(n.path("error").asText(s));
            return n;
        }
    }

    public static JsonNode query(String sql, Map<String, Object> params) {
        return project(J.obj().put("sql", sql).set("params", J.OM.valueToTree(params == null ? Map.of() : params)));
    }

    /** Full projection request: sql/params plus jq, fields, body, bodyHead, reveal, raw, parse. */
    public static JsonNode project(ObjectNode q) { return call("/q", q, true).path("out"); }

    public static JsonNode one(ObjectNode q, String id) {
        var rows = project(q);
        if (rows.size() == 0) throw new IllegalStateException("not found: " + id);
        return rows.get(0);
    }
}
