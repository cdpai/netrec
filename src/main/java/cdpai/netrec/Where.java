package cdpai.netrec;

import java.util.*;

/** The σ half: dynamic where clauses with comma-lists and 4xx-style status specs. */
public final class Where {
    final List<String> clauses = new ArrayList<>();
    final Map<String, Object> params = new LinkedHashMap<>();
    int n;

    public Where eq(String field, Object value) { clauses.add(field + " = :" + p(value)); return this; }

    public Where like(String field, String sub) { clauses.add(field + " like :" + p("%" + sub + "%")); return this; }

    public Where gte(String field, Object value) { clauses.add(field + " >= :" + p(value)); return this; }

    public Where lt(String field, Object value) { clauses.add(field + " < :" + p(value)); return this; }

    public boolean empty() { return clauses.isEmpty(); }

    public String where() { return String.join(" and ", clauses); }

    public Where in(String field, List<String> values, boolean upper) {
        var vals = values.stream().map(v -> upper ? v.trim().toUpperCase() : v.trim()).filter(v -> !v.isEmpty()).toList();
        if (vals.isEmpty()) return this;
        if (vals.size() == 1) return eq(field, vals.get(0));
        clauses.add("(" + String.join(" or ", vals.stream().map(v -> field + " = :" + p(v)).toList()) + ")");
        return this;
    }

    /** 200 | 404,500 | 4xx | 2xx,4xx */
    public Where status(String spec) {
        var or = new ArrayList<String>();
        for (var raw : spec.split(",")) {
            var s = raw.trim().toLowerCase();
            if (s.matches("[1-5]xx")) {
                var base = (s.charAt(0) - '0') * 100;
                or.add("(status >= :" + p(base) + " and status < :" + p(base + 100) + ")");
            } else if (s.matches("\\d{3}")) or.add("status = :" + p(Integer.parseInt(s)));
            else throw new IllegalArgumentException("bad --status (use 404, 4xx or a comma list): " + raw);
        }
        clauses.add("(" + String.join(" or ", or) + ")");
        return this;
    }

    public String sql(String select, String type, String order, int limit) {
        return "select " + select + " from " + type
            + (clauses.isEmpty() ? "" : " where " + String.join(" and ", clauses))
            + (order == null ? "" : " order by " + order)
            + (limit > 0 ? " limit " + limit : "");
    }

    public Map<String, Object> params() { return params; }

    String p(Object v) { var k = "p" + (n++); params.put(k, v); return k; }
}
