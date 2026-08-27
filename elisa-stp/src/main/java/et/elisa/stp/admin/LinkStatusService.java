package et.elisa.stp.admin;

import com.microjainslee.ra.jss7.Ss7ResourceAdaptor;
import com.microjainslee.ra.jss7.admin.Ss7LinkStatusSnapshot;

import et.elisa.stp.config.Ss7PlaneStore;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.Map;

/** Live link/RA status for the dashboard partials and JSON snapshot. */
@ApplicationScoped
public class LinkStatusService {
    private volatile Ss7ResourceAdaptor ss7Ra;
    private volatile boolean ss7IntentionallyStopped;
    private volatile String ss7AppliedDetail = "down";
    private volatile boolean httpListen;
    private volatile String httpDetail = "down";
    private volatile String nodeId = "?";
    private volatile String haMode = "?";

    @Inject
    Ss7PlaneStore planeStore;

    public void bindSs7(Ss7ResourceAdaptor ra) { bindSs7(ra, "bound"); }

    public void bindSs7(Ss7ResourceAdaptor ra, String detail) {
        this.ss7Ra = ra;
        this.ss7IntentionallyStopped = false;
        if (detail != null && !detail.isBlank()) {
            this.ss7AppliedDetail = detail;
        }
    }

    public void clearSs7() {
        this.ss7Ra = null;
        this.ss7AppliedDetail = "cleared";
    }

    public void markSs7Stopped() {
        ss7IntentionallyStopped = true;
        ss7Ra = null;
        ss7AppliedDetail = "stopped";
    }

    public void setSs7AppliedDetail(String detail) {
        this.ss7AppliedDetail = detail == null ? "" : detail;
    }

    private volatile String transitDetail = "down";

    public void setTransitDetail(String detail) {
        this.transitDetail = detail == null ? "down" : detail;
    }

    public void markHttpListen(int port) {
        httpListen = true;
        httpDetail = "listen:" + port;
    }

    public void clearHttp() {
        httpListen = false;
        httpDetail = "down";
    }

    public void setHttpDetail(String detail) {
        httpDetail = detail == null ? "" : detail;
    }

    public void setHa(String nodeId, String haMode) {
        this.nodeId = nodeId == null ? "?" : nodeId;
        this.haMode = haMode == null ? "?" : haMode;
    }

    public boolean isM3uaRouteReady() {
        Ss7ResourceAdaptor ra = ss7Ra;
        return !ss7IntentionallyStopped && ra != null && ra.isM3uaRouteReady();
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        boolean routeReady = isM3uaRouteReady();
        Ss7ResourceAdaptor ra = ss7Ra;
        m.put("ha.nodeId", nodeId);
        m.put("ha.mode", haMode);
        m.put("ss7.live", routeReady);
        m.put("ss7.raActive", ra != null && ra.isActive());
        m.put("sctp.associationUp", ra != null && ra.isSctpAssociationUp());
        m.put("m3ua.asActive", ra != null && ra.isM3uaAsActive());
        m.put("ss7.detail", synthesizeSs7Detail(routeReady, ra != null && ra.isActive()));
        m.put("transit.detail", transitDetail);
        m.put("http.listen", httpListen);
        m.put("http.detail", httpDetail);
        appendPlaneKeys(m, ra, Ss7PlaneStore.PLANE_OUTER);
        appendPlaneKeys(m, ra, Ss7PlaneStore.PLANE_INNER);
        return m;
    }

    /**
     * Per-plane link health (B3 observability): joins the live RA snapshot rows
     * against each plane fragment so operators see OUTER (upper STP / HLR /
     * MSC) versus INNER (USSD / SMSC / GMLC / silent-auth) separately.
     */
    private void appendPlaneKeys(Map<String, Object> target, Ss7ResourceAdaptor ra, String plane) {
        try {
            Map<String, Object> capture = ra == null ? Map.of()
                    : Ss7LinkStatusSnapshot.capture(ra, "stp-relay");
            target.putAll(PlaneLinkStatus.keys(plane,
                    planeStore == null ? null : planeStore.plane(plane), capture));
        } catch (RuntimeException ex) {
            target.put("ss7." + plane + ".detail", "error: " + ex.getMessage());
        }
    }

    /** Rich view for the admin SS7 page tables (links + AS per plane). */
    public PlaneLinkStatus.PlaneView planeView(String plane) {
        Ss7ResourceAdaptor ra = ss7IntentionallyStopped ? null : ss7Ra;
        Map<String, Object> capture;
        try {
            capture = ra == null ? Map.of() : Ss7LinkStatusSnapshot.capture(ra, "stp-relay");
        } catch (RuntimeException ex) {
            capture = Map.of();
        }
        String fragment = planeStore == null ? null : safePlane(plane);
        return PlaneLinkStatus.view(plane, fragment, capture);
    }

    private String safePlane(String plane) {
        try {
            return planeStore.plane(plane);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    public String htmlPartial() {
        Map<String, Object> s = snapshot();
        boolean ss7Live = Boolean.TRUE.equals(s.get("ss7.live"));
        String transit = String.valueOf(s.get("transit.detail"));
        boolean transitUp = transit.startsWith("transit=on");
        boolean listen = Boolean.TRUE.equals(s.get("http.listen"));
        return "<div class=\"grid gap-3 lg:grid-cols-3\">"
                + planeCard("SS7 (M3UA/SCCP)", ss7Live, String.valueOf(s.get("ss7.detail")), "/admin/ss7")
                + planeCard("SS7 OUTER — core", Boolean.TRUE.equals(s.get("ss7.outer.live")),
                        String.valueOf(s.get("ss7.outer.detail")), "/admin/ss7#outer")
                + planeCard("SS7 INNER — services", Boolean.TRUE.equals(s.get("ss7.inner.live")),
                        String.valueOf(s.get("ss7.inner.detail")), "/admin/ss7#inner")
                + planeCard("Transit (GTT/ACL)", transitUp, transit, "/admin/transit")
                + planeCard("HTTP Admin", listen, String.valueOf(s.get("http.detail")), "/admin")
                + "<div class=\"form-card rounded-lg border border-ink-line bg-ink-panel/80 p-4\">"
                + "<h3 class=\"text-sm font-semibold tracking-wide text-slate-100\">HA node</h3>"
                + "<p class=\"link-status-detail mt-2\">node=" + esc(s.get("ha.nodeId"))
                + " mode=" + esc(s.get("ha.mode")) + "</p></div>"
                + "</div>";
    }

    private static String planeCard(String name, boolean live, String detail, String href) {
        String badge = live
                ? "<span class=\"link-status-badge link-status-badge--ok\">LIVE</span>"
                : "<span class=\"link-status-badge link-status-badge--mute\">DOWN</span>";
        return "<div class=\"form-card rounded-lg border border-ink-line bg-ink-panel/80 p-4 link-status-panel\">"
                + "<div class=\"link-status-head\"><h3 class=\"text-sm font-semibold tracking-wide text-slate-100\">"
                + esc(name) + "</h3>" + badge + "</div>"
                + "<p class=\"link-status-detail mt-2\">" + esc(detail) + "</p>"
                + "<p class=\"mt-3 text-xs\"><a class=\"text-signal hover:underline\" href=\""
                + esc(href) + "\">Open</a></p></div>";
    }

    private String synthesizeSs7Detail(boolean routeReady, boolean raActive) {
        if (ss7IntentionallyStopped) return "ss7=stopped";
        if (routeReady) return ss7AppliedDetail.contains("ss7=") ? ss7AppliedDetail : "ss7=route-ready";
        if (raActive) return "ss7=listening;peer=down";
        return ss7AppliedDetail == null || ss7AppliedDetail.isBlank() ? "ss7=down" : ss7AppliedDetail;
    }

    private static String esc(Object o) {
        if (o == null) return "";
        return o.toString().replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }
}
