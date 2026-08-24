package littlejlib.netrec;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** Record-shape projection (the π to `ls`'s σ): header parsing, body selection, JSON body parsing, field pruning. */
public final class Project {
    static final List<String> HEADERS = List.of("reqHeaders", "reqExtraHeaders", "respHeaders");
    static final List<String> BODIES = List.of("reqBody", "respBody");

    private Project() {}

    /** Headers become objects (masked unless reveal); JSON bodies also appear parsed as .req / .resp. */
    public static ObjectNode enrich(ObjectNode r, boolean reveal, boolean parse) {
        for (var f : HEADERS) if (r.has(f)) r.set(f, J.headers(r.path(f).asText(), reveal));
        if (parse) {
            if (r.has("reqBody")) put(r, "req", r.path("reqBody").asText());
            if (r.has("respBody")) put(r, "resp", r.path("respBody").asText());
        }
        return r;
    }

    static void put(ObjectNode r, String field, String raw) {
        var n = J.tryParse(raw);
        if (n != null) r.set(field, n);
    }

    /** Keep headers as stored strings (curl needs that) but mask secrets inside them. */
    public static ObjectNode maskRaw(ObjectNode r) {
        for (var f : HEADERS) {
            if (!r.has(f)) continue;
            var n = J.headers(r.path(f).asText(), false);
            if (n.isObject()) r.put(f, n.toString());
        }
        return r;
    }

    public static ObjectNode body(ObjectNode r, String mode, int head) {
        var m = mode == null ? "both" : mode.toLowerCase();
        if (!List.of("both", "req", "resp", "none").contains(m))
            throw new IllegalArgumentException("--body must be req|resp|both|none");
        if (m.equals("none") || m.equals("req")) r.remove(List.of("respBody", "resp"));
        if (m.equals("none") || m.equals("resp")) r.remove(List.of("reqBody", "req"));
        if (head > 0) for (var f : BODIES) {
            if (!r.has(f)) continue;
            var v = r.path(f).asText();
            if (v.length() > head) { r.put(f, v.substring(0, head)); r.put(f + "Head", head); }
        }
        return r;
    }

    public static ObjectNode fields(ObjectNode r, List<String> keep) {
        var out = J.OM.createObjectNode();
        for (var k : keep) if (r.has(k.trim())) out.set(k.trim(), r.get(k.trim()));
        return out;
    }
}
