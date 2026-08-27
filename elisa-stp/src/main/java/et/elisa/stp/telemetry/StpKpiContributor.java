package et.elisa.stp.telemetry;

import java.util.Map;
import java.util.TreeMap;

import com.microjainslee.admin.RaAdminApiRegistrar;
import com.microjainslee.admin.RaAdminDashboardContributor;
import com.microjainslee.admin.RaAdminHttpResponse;
import com.microjainslee.admin.RaAdminJson;
import com.microjainslee.admin.RaAdminManifest;

/**
 * STP-owned Monitor Hub pack: transit-plane KPIs (relay forwarded/rejected,
 * ACL denies, GTT hits/unrouted). Registered by {@code AdminHttpHandler.buildHub}
 * so fast-jar classloader quirks never leave the STP tab out of the hub.
 */
public final class StpKpiContributor implements RaAdminDashboardContributor {

    @Override
    public RaAdminManifest manifest() {
        return RaAdminManifest.of("stp-kpi", "stp", "STP KPI", 5);
    }

    @Override
    public void registerApis(RaAdminApiRegistrar registrar) {
        registrar.get("/status", req -> RaAdminJson.ok(Map.of("counters", StpKpi.snapshot())));
        registrar.get("/status.html", req -> RaAdminHttpResponse.text(
                200, "text/html; charset=utf-8", statusHtml()));
    }

    static String statusHtml() {
        Map<String, Long> snap = new TreeMap<>(StpKpi.snapshot());
        StringBuilder sb = new StringBuilder(2048);
        sb.append("<div class=\"link-status-panel\">")
                .append("<div class=\"link-status-head\"><h3>Relay</h3>")
                .append(badge(snap, "relay.forwarded", "relay.rejected"))
                .append("</div>");
        sb.append(tableOpen(new String[]{"metric", "count"}));
        appendIfPositive(sb, snap, "relay.forwarded", "unit-data forwarded");
        appendIfPositive(sb, snap, "relay.rejected", "unit-data rejected");
        appendIfPositive(sb, snap, "gtt.translated", "GTT hits");
        appendIfPositive(sb, snap, "gtt.unrouted", "GTT misses (unrouted)");
        appendIfPositive(sb, snap, "acl.denied", "ACL denies (default-deny)");
        sb.append("</table></div>");

        sb.append("<div class=\"link-status-panel\">")
                .append("<div class=\"link-status-head\"><h3>ACL denies by peer OPC</h3></div>")
                .append(tableOpen(new String[]{"OPC", "denies"}));
        boolean any = false;
        for (Map.Entry<String, Long> e : snap.entrySet()) {
            if (e.getKey().startsWith("acl.denied.opc.")) {
                any = true;
                sb.append(row(e.getKey().substring("acl.denied.opc.".length()), e.getValue()));
            }
        }
        if (!any) {
            sb.append(row("— none —", 0));
        }
        sb.append("</table></div>");
        return sb.toString();
    }

    private static String badge(Map<String, Long> snap, String ok, String bad) {
        long o = v(snap, ok);
        long b = v(snap, bad);
        boolean healthy = b == 0;
        return "<span class=\"link-status-badge "
                + (healthy ? "link-status-badge--ok\">LIVE" : "link-status-badge--mute\">DROPS")
                + "</span>";
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