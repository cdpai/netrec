package cdpai.netrec;

import com.arcadedb.database.Database;
import com.arcadedb.query.sql.executor.Result;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** In-process DB access, used only inside the daemon. Readers reach it through the ctl API. */
public final class Store {
    Database db;

    public Store(Database db) { this.db = db; }

    /** After a wipe the old handle is dead; every holder sees the new one through this. */
    public void rebind(Database fresh) { this.db = fresh; }

    public List<ObjectNode> query(String sql, Map<String, Object> params) {
        var out = new ArrayList<ObjectNode>();
        try (var rs = db.query("sql", sql, params == null ? Map.of() : params)) {
            while (rs.hasNext()) out.add(node(rs.next()));
        }
        return out;
    }

    static ObjectNode node(Result r) {
        var n = J.OM.createObjectNode();
        for (var k : r.getPropertyNames())
            if (!k.startsWith("@")) n.set(k, J.OM.valueToTree(r.getProperty(k)));
        return n;
    }

    public void insert(String type, ObjectNode n) {
        db.transaction(() -> {
            var doc = db.newDocument(type);
            n.fields().forEachRemaining(e -> doc.set(e.getKey(), value(e.getValue())));
            doc.save();
        });
    }

    public long count(String type) { return count(type, null, null); }

    public long count(String type, String where, Map<String, Object> params) {
        var rows = query("select count(*) as n from " + type + clause(where), params);
        return rows.isEmpty() ? 0 : rows.get(0).path("n").asLong();
    }

    public long delete(String type, String where, Map<String, Object> params) {
        var n = new long[1];
        db.transaction(() -> {
            try (var rs = db.command("sql", "delete from " + type + clause(where), params == null ? Map.of() : params)) {
                if (rs.hasNext()) n[0] = rs.next().<Number>getProperty("count").longValue();
            }
        });
        return n[0];
    }

    static String clause(String where) { return where == null || where.isBlank() ? "" : " where " + where; }

    static Object value(JsonNode v) {
        if (v.isInt()) return v.asInt();
        if (v.isLong()) return v.asLong();
        if (v.isBoolean()) return v.asBoolean();
        if (v.isNumber()) return v.asDouble();
        return v.asText();
    }
}
