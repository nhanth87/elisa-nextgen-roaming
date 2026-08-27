package et.elisa.iwf.admin;

import et.elisa.iwf.IwfConfig;
import et.elisa.iwf.mapping.AvpTransform;
import et.elisa.iwf.mapping.MappingEntry;
import et.elisa.iwf.mapping.Ts29305Table;
import et.elisa.iwf.telemetry.IwfKpi;

import java.util.List;
import java.util.Map;

final class AdminIwfRenderer {
    private AdminIwfRenderer() {}

    static String diameterStatusHtml(Map<String, Object> status) {
        boolean sctpUp = Boolean.TRUE.equals(status.get("sctp.associationUp"));
        boolean open = Boolean.TRUE.equals(status.get("open"));
        boolean ready = sctpUp && open;

        StringBuilder out = new StringBuilder(2048);
        out.append("<div class=\"ss7-verdict ss7-verdict--")
                .append(ready ? "live" : "down").append("\">")
                .append("<div><p class=\"ss7-kiper\">Diameter readiness</p><h2>")
                .append(ready ? "Diameter route ready" : "Diameter route unavailable")
                .append("</h2><p>").append(esc(status.get("detail"))).append("</p></div>")
                .append("<span class=\"ss7-verdict-badge\">")
                .append(ready ? "LIVE" : "DOWN").append("</span></div>");

        out.append("<div class=\"ss7-signal-grid\">")
                .append(signal("SCTP association", sctpUp, sctpUp ? "UP" : "DOWN",
                        "Transport to DRA must be established"))
                .append(signal("Diameter session", open, open ? "OPEN" : "CLOSED",
                        "CER/CEA exchange complete"))
                .append(signal("Application", true, "S6a/S6d",
                        "App-Id 16777251 (TS 29.272)"))
                .append("</div>");
        return out.toString();
    }

    static String mapStatusHtml(Map<String, Object> status) {
        boolean active = Boolean.TRUE.equals(status.get("active"));

        StringBuilder out = new StringBuilder(2048);
        out.append("<div class=\"ss7-verdict ss7-verdict--")
                .append(active ? "live" : "down").append("\">")
                .append("<div><p class=\"ss7-kiper\">MAP leg readiness</p><h2>")
                .append(active ? "MAP leg active" : "MAP leg inactive (NoopMapLeg)")
                .append("</h2><p>TCAP/MAP via ra-jss7 — M-IWF-3</p></div>")
                .append("<span class=\"ss7-verdict-badge\">")
                .append(active ? "ACTIVE" : "INACTIVE").append("</span></div>");

        out.append("<div class=\"ss7-signal-grid\">")
                .append(signal("MAP service", active, active ? "ACTIVE" : "INACTIVE",
                        "ra-jss7 SSN + GT binding"))
                .append(signal("SSN", true, String.valueOf(status.getOrDefault("ssn", "—")),
                        "TS 29.002 Sub-Service Number"))
                .append(signal("Global Title", true, String.valueOf(status.getOrDefault("gt", "—")),
                        "E.164 MAP GT for inbound dialogs"))
                .append("</div>");
        return out.toString();
    }

    static String routingTableHtml() {
        List<MappingEntry> entries = Ts29305Table.all();
        StringBuilder out = new StringBuilder(4096);
        out.append("<table class=\"w-full text-sm\"><thead><tr>")
                .append("<th class=\"text-left p-2\">MAP Operation</th>")
                .append("<th class=\"text-left p-2\">Diameter Command</th>")
                .append("<th class=\"text-center p-2\">MAP→DIA</th>")
                .append("<th class=\"text-center p-2\">DIA→MAP</th>")
                .append("<th class=\"text-left p-2\">AVP Transforms</th>")
                .append("<th class=\"text-left p-2\">Spec Reference</th>")
                .append("<th class=\"text-center p-2\">Status</th>")
                .append("</tr></thead><tbody>");

        for (MappingEntry e : entries) {
            out.append("<tr class=\"border-t border-ink-line\">")
                    .append("<td class=\"p-2 font-mono\">")
                    .append(esc(e.mapOp().name())).append(" (").append(e.mapOp().opCode()).append(")</td>")
                    .append("<td class=\"p-2 font-mono\">")
                    .append(esc(e.diaCmd().name())).append(" (").append(e.diaCmd().cmdCode()).append(")</td>")
                    .append("<td class=\"p-2 text-center\">").append(e.mapToDia() ? "→" : "—").append("</td>")
                    .append("<td class=\"p-2 text-center\">").append(e.diaToMap() ? "←" : "—").append("</td>")
                    .append("<td class=\"p-2\">").append(transformsHtml(e.transforms())).append("</td>")
                    .append("<td class=\"p-2 text-xs text-ink-mute\">").append(esc(e.specRef())).append("</td>")
                    .append("<td class=\"p-2 text-center\">").append(statusBadge(e.status())).append("</td>")
                    .append("</tr>");
        }
        out.append("</tbody></table>");
        return out.toString();
    }

    static String transformsHtml(List<AvpTransform> transforms) {
        if (transforms.isEmpty()) return "<span class=\"text-ink-mute\">—</span>";
        StringBuilder b = new StringBuilder();
        for (AvpTransform t : transforms) {
            b.append("<div class=\"flex items-center gap-2 text-xs font-mono py-0.5\">")
                    .append("<span class=\"text-ink-mute\">").append(esc(t.mapSource())).append("</span>")
                    .append("<span class=\"text-signal\">→</span>")
                    .append("<span>").append(esc(t.diaAvpName())).append("</span>")
                    .append("<span class=\"text-ink-mute\">avp:").append(t.diaAvpCode()).append("</span>")
                    .append("<span class=\"text-ink-mute\">(").append(esc(t.kind().name())).append(")</span>")
                    .append(t.required() ? "<span class=\"text-rose-400\">*</span>" : "")
                    .append("</div>");
        }
        return b.toString();
    }

    static String statusBadge(MappingEntry.Status status) {
        String cls = switch (status) {
            case PLANNED -> "bg-ink-mute/20 text-ink-mute";
            case MAPPED -> "bg-signal/20 text-signal";
            case LAB_VERIFIED -> "bg-emerald-500/20 text-emerald-400";
        };
        return "<span class=\"inline-block rounded px-2 py-0.5 text-xs font-semibold " + cls + "\">"
                + status.name() + "</span>";
    }

    static String kpiHtml() {
        Map<String, Long> snap = IwfKpi.snapshot();
        if (snap.isEmpty()) {
            return "<p class=\"text-ink-mute text-sm\">No counters yet — traffic will populate this view.</p>";
        }
        StringBuilder out = new StringBuilder(2048);
        out.append("<div class=\"grid grid-cols-2 md:grid-cols-4 gap-3\">");
        for (var e : snap.entrySet()) {
            out.append("<div class=\"rounded-lg border border-ink-line bg-ink-panel/80 p-3\">")
                    .append("<p class=\"text-xs text-ink-mute\">").append(esc(e.getKey())).append("</p>")
                    .append("<p class=\"text-xl font-semibold text-slate-100 mt-1\">").append(e.getValue()).append("</p>")
                    .append("</div>");
        }
        out.append("</div>");
        return out.toString();
    }

    static String configJson(IwfConfig c) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(IwfConfig.asMap(c));
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String signal(String label, boolean ok, String value, String detail) {
        return "<article class=\"ss7-signal\"><p>" + esc(label)
                + "</p><strong class=\"" + (ok ? "ss7-state-ok" : "ss7-state-down") + "\">"
                + esc(value) + "</strong><span>" + esc(detail) + "</span></article>";
    }

    private static String esc(Object value) {
        return AdminPageRenderer.esc(value);
    }
}
