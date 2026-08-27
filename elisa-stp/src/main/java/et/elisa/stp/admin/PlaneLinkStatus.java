package et.elisa.stp.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Joins one editable SS7 plane fragment ({@code configs/ss7-outer.json} /
 * {@code ss7-inner.json}) against the live
 * {@code Ss7LinkStatusSnapshot.capture()} rows so the dashboard can show which
 * links belong to the OUTER plane (upper STP / HLR / MSC) versus the INNER
 * plane (USSD / SMSC / GMLC / silent-auth) and whether each is up.
 *
 * <p>Pure and null-tolerant: a blank or unparseable fragment yields an empty
 * view (never an exception), so a broken edit cannot take the status page
 * down.</p>
 */
public final class PlaneLinkStatus {

    private static final ObjectMapper JSON = new ObjectMapper();

    public record LinkRow(String name, String peer, String state, boolean up) {
    }

    public record AsRow(String name, String state, boolean active) {
    }

    public record PlaneView(String plane, Map<String, Object> keys,
                            List<LinkRow> links, List<AsRow> ases) {
    }

    private PlaneLinkStatus() {
    }

    /**
     * Builds the per-plane status view.
     *
     * @param plane        "outer" | "inner"
     * @param fragmentJson plane fragment JSON (may be null/blank/broken)
     * @param capture      {@code Ss7LinkStatusSnapshot.capture(ra, raName)} output
     *                     (may be null when no RA is wired)
     */
    public static PlaneView view(String plane, String fragmentJson, Map<String, Object> capture) {
        List<String> linkNames = new ArrayList<>();
        List<String> asNames = new ArrayList<>();
        parseFragment(fragmentJson, linkNames, asNames);

        List<LinkRow> links = new ArrayList<>();
        for (String name : linkNames) {
            JsonNode assoc = findEntry(capture, "associations", name);
            if (assoc == null) {
                // server-only links surface via servers[] as "<link>-srv"
                JsonNode srv = findEntry(capture, "servers", name + "-srv");
                if (srv != null) {
                    String st = text(srv, "state");
                    boolean listen = "LISTEN".equals(st);
                    links.add(new LinkRow(name, text(srv, "local"),
                            listen ? "LISTEN" : "DOWN", listen));
                    continue;
                }
                links.add(new LinkRow(name, "", "DOWN", false));
                continue;
            }
            String state = text(assoc, "state");
            Boolean connected = asBool(assoc, "connected");
            boolean up = "UP".equals(state)
                    || Boolean.TRUE.equals(connected)
                    || Boolean.TRUE.equals(asBool(assoc, "up"));
            links.add(new LinkRow(name, text(assoc, "peer"), state == null ? "?" : state, up));
        }

        List<AsRow> ases = new ArrayList<>();
        for (String name : asNames) {
            JsonNode as = findEntry(capture, "applicationServers", name);
            if (as == null) {
                ases.add(new AsRow(name, "AS-DOWN", false));
                continue;
            }
            String state = text(as, "state");
            boolean active = state != null && state.toUpperCase().contains("ACTIVE")
                    && !state.toUpperCase().contains("PENDING");
            ases.add(new AsRow(name, state == null ? "?" : state, active));
        }

        long linksUp = links.stream().filter(LinkRow::up).count();
        long asActive = ases.stream().filter(AsRow::active).count();
        boolean live = !links.isEmpty() && linksUp == links.size();

        Map<String, Object> keys = new LinkedHashMap<>();
        keys.put("ss7." + plane + ".live", live);
        keys.put("ss7." + plane + ".linksUp", linksUp);
        keys.put("ss7." + plane + ".linksTotal", (long) links.size());
        keys.put("ss7." + plane + ".asActive", asActive);
        keys.put("ss7." + plane + ".asTotal", (long) ases.size());
        keys.put("ss7." + plane + ".detail",
                "links=" + linksUp + "/" + links.size()
                        + " asActive=" + asActive + "/" + ases.size());
        return new PlaneView(plane, keys, links, ases);
    }

    /** Snapshot keys for one plane, ready to merge into {@code /admin/status.json}. */
    public static Map<String, Object> keys(String plane, String fragmentJson,
                                           Map<String, Object> capture) {
        return view(plane, fragmentJson, capture).keys();
    }

    // ---- helpers -----------------------------------------------------------

    private static void parseFragment(String json, List<String> linkNames, List<String> asNames) {
        if (json == null || json.isBlank()) return;
        try {
            JsonNode root = JSON.readTree(json);
            for (JsonNode l : root.path("sctp").path("links")) {
                String n = l.path("name").asText(null);
                if (n != null && !n.isBlank()) linkNames.add(n);
            }
            for (JsonNode a : root.path("m3ua").path("as")) {
                String n = a.path("name").asText(null);
                if (n != null && !n.isBlank()) asNames.add(n);
            }
        } catch (Exception ignored) {
            // broken fragment → empty view
        }
    }

    private static JsonNode findEntry(Map<String, Object> capture, String arrayKey, String name) {
        if (capture == null || name == null) return null;
        Object arr = capture.get(arrayKey);
        if (!(arr instanceof List<?> list)) return null;
        for (Object o : list) {
            if (o instanceof Map<?, ?> m && name.equals(String.valueOf(m.get("name")))) {
                return JSON.valueToTree(m);
            }
        }
        return null;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static Boolean asBool(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v != null && v.isBoolean() ? v.asBoolean() : null;
    }
}
