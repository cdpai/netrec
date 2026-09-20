package cdpai.netrec;

import com.fasterxml.jackson.databind.JsonNode;
import net.thisptr.jackson.jq.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** jq expressions, evaluated inside the daemon so records shrink before they are serialized. */
public final class Jq {
    static final Scope ROOT = root();
    static final Map<String, JsonQuery> CACHE = new ConcurrentHashMap<>();

    private Jq() {}

    static Scope root() {
        var s = Scope.newEmptyScope();
        BuiltinFunctionLoader.getInstance().loadFunctions(Versions.JQ_1_6, s);
        return s;
    }

    public static List<JsonNode> apply(String expr, JsonNode in) {
        var q = CACHE.computeIfAbsent(expr, e -> {
            try { return JsonQuery.compile(e, Versions.JQ_1_6); }
            catch (Exception ex) { throw new IllegalArgumentException("bad jq expression: " + ex.getMessage()); }
        });
        var out = new ArrayList<JsonNode>();
        try { q.apply(Scope.newChildScope(ROOT), in, out::add); }
        catch (Exception ex) { throw new IllegalStateException("jq: " + ex.getMessage()); }
        return out;
    }
}
