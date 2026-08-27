package et.elisa.iwf.telemetry;

import java.util.Map;
import java.util.TreeMap;

import com.microjainslee.admin.RaAdminApiRegistrar;
import com.microjainslee.admin.RaAdminDashboardContributor;
import com.microjainslee.admin.RaAdminHttpResponse;
import com.microjainslee.admin.RaAdminJson;
import com.microjainslee.admin.RaAdminManifest;

/**
 * IWF Monitor Hub pack: protocol KPIs (MAP requests/responses per operation,
 * Diameter requests/responses per operation, TCAP dialog outcomes, binding
 * lifecycle events, mapping engine dispatches).
 *
 * <p>Registered via ServiceLoader — see
 * {@code META-INF/services/com.microjainslee.admin.RaAdminDashboardContributor}.</p>
 */
public final class IwfKpiContributor implements RaAdminDashboardContributor {

    @Override
    public RaAdminManifest manifest() {
        return RaAdminManifest.of("iwf", "kpi", "IWF KPI", 5);
    }

    @Override
    public void registerApis(RaAdminApiRegistrar registrar) {
        registrar.get("/status", req -> RaAdminJson.ok(Map.of(
                "counters", IwfKpi.snapshot())));
        registrar.get("/status.html", req -> RaAdminHttpResponse.text(
                200, "text/html; charset=utf-8", statusHtml()));
    }

    /** HTMX fragment: MAP operations + Diameter operations + TCAP + bindings tables. */
    static String statusHtml() {
        Map<String, Long> snap = new TreeMap<>(IwfKpi.snapshot());
        StringBuilder sb = new StringBuilder(4096);
        sb.append("<div class=\"link-status-panel\">");

        // MAP operations table
        sb.append("<div class=\"link-status-head\"><h3>MAP operations</h3>")
                .append(badge("map.request", "map.response.fail", snap))
                .append("</div>");
        sb.append(tableOpen(new String[]{"operation", "requests", "success", "failed"}));
        for (String op : new String[]{"ugl", "sai", "purge_ms", "cancel_location",
                "insert_subscriber_data", "purge"}) {
            long req = v(snap, "map.request." + op);
            long ok = v(snap, "map.response.success." + op);
            long fail = v(snap, "map.response.fail." + op);
            if (req == 0 && ok == 0 && fail == 0) {
                continue;
            }
            sb.append(row(op.toUpperCase(), req, ok, fail));
        }
        sb.append(row("<b>total</b>",
                v(snap, "map.request"),
                v(snap, "map.response.success"),
                v(snap, "map.response.fail")));
        sb.append("</table></div>");

        // Diameter operations table
        sb.append("<div class=\"link-status-head\"><h3>Diameter operations</h3>")
                .append(badge("dia.request", "dia.response.fail", snap))
                .append("</div>");
        sb.append(tableOpen(new String[]{"operation", "requests", "success", "failed"}));
        for (String op : new String[]{"ulr", "air", "pur", "nor", "clr", "idr", "dsr"}) {
            long req = v(snap, "dia.request." + op);
            long ok = v(snap, "dia.response.success." + op);
            long fail = v(snap, "dia.response.fail." + op);
            if (req == 0 && ok == 0 && fail == 0) {
                continue;
            }
            sb.append(row(op.toUpperCase(), req, ok, fail));
        }
        sb.append(row("<b>total</b>",
                v(snap, "dia.request"),
                v(snap, "dia.response.success"),
                v(snap, "dia.response.fail")));
        sb.append("</table></div>");

        // TCAP dialogs table
        sb.append("<div class=\"link-status-panel\">")
                .append("<div class=\"link-status-head\"><h3>TCAP dialogs</h3></div>")
                .append(tableOpen(new String[]{"outcome", "count"}));
        for (String kind : new String[]{"accept", "delimiter", "close", "release",
                "reject", "user_abort", "provider_abort", "timeout"}) {
            appendIfPositive(sb, snap, "tcap.dialog." + kind, kind);
        }
        sb.append("</table></div>");

        // Binding lifecycle table
        sb.append("<div class=\"link-status-panel\">")
                .append("<div class=\"link-status-head\"><h3>Binding lifecycle</h3></div>")
                .append(tableOpen(new String[]{"event", "count"}));
        for (String ev : new String[]{"created", "expired", "hit", "miss"}) {
            appendIfPositive(sb, snap, "binding." + ev, ev);
        }
        sb.append("</table></div>");

        // Mapping dispatch table
        sb.append("<div class=\"link-status-panel\">")
                .append("<div class=\"link-status-head\"><h3>Mapping dispatch</h3></div>")
                .append(tableOpen(new String[]{"operation", "count"}));
        for (String op : new String[]{"ulr", "air", "pur", "nor", "clr", "idr", "dsr"}) {
            appendIfPositive(sb, snap, "mapping.dispatch." + op, op);
        }
        appendIfPositive(sb, snap, "mapping.dispatch", "total");
        sb.append("</table></div>");

        sb.append("</div>");
        return sb.toString();
    }

    private static String badge(String reqKey, String failKey, Map<String, Long> snap) {
        long fail = v(snap, failKey);
        String cls = fail == 0 ? "link-status-badge--ok" : "link-status-badge--fail";
        String label = fail == 0 ? "NO FAIL" : ("FAIL " + fail);
        return "<span class=\"link-status-badge " + cls + "\">" + label + "</span>";
    }

    private static String tableOpen(String[] headers) {
        StringBuilder sb = new StringBuilder("<div class=\"link-status-table-wrap\">")
                .append("<table class=\"link-status-table\"><thead><tr>");
        for (String h : headers) {
            sb.append("<th>").append(h).append("</th>");
        }
        return sb.append("</tr></thead><tbody>").toString();
    }

    private static String row(String name, Object... cells) {
        StringBuilder sb = new StringBuilder("<tr><td>").append(name).append("</td>");
        for (Object c : cells) {
            sb.append("<td>").append(c).append("</td>");
        }
        return sb.append("</tr>").toString();
    }

    private static void appendIfPositive(StringBuilder sb, Map<String, Long> snap,
                                         String key, String label) {
        long val = v(snap, key);
        if (val > 0) {
            sb.append(row(label, val));
        }
    }

    private static long v(Map<String, Long> snap, String key) {
        Long val = snap.get(key);
        return val == null ? 0L : val;
    }
}
