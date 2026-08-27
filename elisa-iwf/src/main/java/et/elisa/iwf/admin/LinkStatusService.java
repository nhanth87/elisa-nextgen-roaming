package et.elisa.iwf.admin;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Tracks MAP and Diameter link status for the admin dashboard.
 * Updated by {@link et.elisa.iwf.service.Ss7ApplyService} and
 * {@link et.elisa.iwf.diameter.CorsacDiameterLeg}.
 */
@ApplicationScoped
public class LinkStatusService {

    private volatile boolean mapApplied;
    private volatile String mapAppliedDetail = "ss7=not-started";
    private volatile boolean diaApplied;
    private volatile String diaAppliedDetail = "dia=not-started";

    // ── MAP (SS7) ──

    public void setMapApplied(String detail) {
        this.mapApplied = true;
        this.mapAppliedDetail = detail;
    }

    public void clearMap() {
        this.mapApplied = false;
        this.mapAppliedDetail = "ss7=stopped";
    }

    public boolean isMapApplied() {
        return mapApplied;
    }

    public String mapAppliedDetail() {
        return mapAppliedDetail;
    }

    // ── Diameter ──

    public void setDiaApplied(String detail) {
        this.diaApplied = true;
        this.diaAppliedDetail = detail;
    }

    public void clearDia() {
        this.diaApplied = false;
        this.diaAppliedDetail = "dia=stopped";
    }

    public boolean isDiaApplied() {
        return diaApplied;
    }

    public String diaAppliedDetail() {
        return diaAppliedDetail;
    }

    // ── snapshot for dashboard ──

    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mapApplied", mapApplied);
        m.put("mapDetail", mapAppliedDetail);
        m.put("diaApplied", diaApplied);
        m.put("diaDetail", diaAppliedDetail);
        return m;
    }
}
