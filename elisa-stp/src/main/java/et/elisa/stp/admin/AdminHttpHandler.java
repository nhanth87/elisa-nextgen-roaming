package et.elisa.stp.admin;

import et.elisa.stp.config.Ss7PlaneStore;
import et.elisa.stp.config.StpTransitConfig;
import et.elisa.stp.service.StpTransitApplyService;
import et.elisa.stp.service.Ss7ApplyService;
import et.elisa.stp.telemetry.AppTelemetry;
import et.elisa.stp.telemetry.StpKpiContributor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microjainslee.admin.AdminDashboardRegistry;
import com.microjainslee.admin.RaAdminDashboardContributor;
import com.microjainslee.admin.RaAdminHttpResponse;
import com.microjainslee.monitor.MonitorHandler;
import com.microjainslee.ra.httpserver.events.HttpUpload;
import com.microjainslee.ra.jss7.admin.Ss7AdminBindings;
import com.microjainslee.telemetry.TelemetryPort;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Admin HTTP surface for the STP transit plane: health, Prometheus metrics,
 * signed-session login/logout, per-plane SS7 pages (outer / inner) with
 * per-connection cards, and read-only status for transit (GTT/ACL) links.
 *
 * <p>Invoked by {@code HttpServerSbb} which adapts the ra-http-server request
 * model ({@code HttpWebRequestEvent} / {@code HttpServerCommand}) into this
 * framework-agnostic router. No Quarkus/JAX-RS bindings are required — the RA
 * owns the socket (see {@code application.properties http.ra.*}).</p>
 */
@ApplicationScoped
public class AdminHttpHandler {
    private static final Logger LOG = LogManager.getLogger(AdminHttpHandler.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Inject LinkStatusService linkStatus;
    @Inject AdminAuthService adminAuth;
    @Inject AdminPageRenderer pages;
    @Inject AdminNavRenderer nav;
    @Inject AppTelemetry appTelemetry;
    @Inject StpTransitApplyService transit;
    @Inject Ss7ApplyService ss7Apply;
    @Inject Ss7PlaneStore planeStore;

    @ConfigProperty(name = "stp.admin.cookie-secure", defaultValue = "false")
    boolean cookieSecure;

    @ConfigProperty(name = "stp.health.require-ss7-live", defaultValue = "false")
    boolean healthRequiresSs7Live;

    @ConfigProperty(name = "stp.admin.monitor-app-name", defaultValue = "Digicom-ET STP")
    String monitorAppName;

    private volatile MonitorHandler monitorHub;

    /**
     * Route one admin HTTP request. Returns {@link Optional#empty()} when the
     * path is not part of the admin surface (caller falls through to a 404).
     */
    public Optional<HttpReply> tryHandle(String method, String path,
                                         Map<String, String> headers,
                                         Map<String, String> query,
                                         String body, List<HttpUpload> uploads) {
        if (path == null) return Optional.empty();
        String p = path.startsWith("/") ? path : "/" + path;
        int qmark = p.indexOf('?');
        if (qmark >= 0) p = p.substring(0, qmark);

        if (p.equals("/health") || p.equals("/healthz")) {
            boolean ss7Live = linkStatus.isM3uaRouteReady();
            boolean degraded = healthRequiresSs7Live && !ss7Live;
            return Optional.of(HttpReply.json(degraded ? 503 : 200, Map.of(
                    "status", degraded ? "DEGRADED" : "UP",
                    "ss7.live", ss7Live,
                    "transit", String.valueOf(transit == null ? null : transit.transitEnabled()))));
        }

        Optional<AdminAuthService.Principal> principal = adminAuth.authenticate(headers, query);

        if (isMonitorHubPath(p)) {
            if (!isPublicMonitorStatic(method, p) && principal.isEmpty()) {
                return Optional.of(HttpReply.text(401, "unauthorized"));
            }
            Optional<RaAdminHttpResponse> hit = monitor().handle(method, p, query, body);
            return hit.map(r -> new HttpReply(r.status(), r.contentType(), r.body(), r.headers()));
        }

        if ("GET".equalsIgnoreCase(method) && (p.equals("/metrics") || p.equals("/metrics/"))) {
            if (principal.isEmpty()) return Optional.of(HttpReply.text(401, "unauthorized"));
            TelemetryPort tp = appTelemetry == null ? null : appTelemetry.port();
            return Optional.of(tp == null
                    ? HttpReply.text(503, "telemetry disabled")
                    : HttpReply.text(200, tp.scrape()));
        }

        if (!(p.startsWith("/admin") || p.equals("/"))) return Optional.empty();

        boolean sessionOk = principal.isPresent() && principal.get().fromSession();
        if (p.equals("/")) return Optional.of(HttpReply.redirect(sessionOk ? "/admin" : "/admin/login"));

        if (p.startsWith("/admin/static/")) {
            try {
                return Optional.of(pages.staticResource(p.substring("/admin/static/".length())));
            } catch (Exception e) {
                return Optional.of(HttpReply.notFound());
            }
        }

        if (p.equals("/admin/login")) {
            if ("POST".equalsIgnoreCase(method)) return Optional.of(handleLogin(body));
            return Optional.of(loginPage(null));
        }
        if (p.equals("/admin/logout")) {
            return Optional.of(HttpReply.redirect("/admin/login")
                    .addSetCookie(SignedSessionCookie.clearCookieHeader(cookieSecure))
                    .addSetCookie(SignedSessionCookie.clearCsrfCookieHeader(cookieSecure)));
        }

        if (principal.isEmpty()) {
            return Optional.of(wantsShell(method, p) ? HttpReply.redirect("/admin/login")
                    : HttpReply.text(401, "unauthorized"));
        }
        AdminAuthService.Principal who = principal.get();

        if ("POST".equalsIgnoreCase(method) && p.equals("/admin/ss7") && who.isAdminOrOps()) {
            if (!sessionCsrfOk(who, headers, body)) {
                return Optional.of(HttpReply.text(403, "Session expired. Reload and sign in again."));
            }
            return Optional.of(handleSs7Action(body));
        }

        // B3 per-plane pages + per-connection cards
        if (p.equals("/admin/ss7/outer") || p.equals("/admin/ss7/inner")) {
            String plane = p.endsWith("/inner") ? Ss7PlaneStore.PLANE_INNER : Ss7PlaneStore.PLANE_OUTER;
            if ("POST".equalsIgnoreCase(method) && who.isAdminOrOps()) {
                Map<String, String> fbody = form(body);
                if (!sessionCsrfOk(who, headers, body)) {
                    String ck = headers == null ? null : headers.get("Cookie");
                    boolean csrfCookiePresent = ck != null && ck.contains(SignedSessionCookie.CSRF_COOKIE_NAME + "=");
                    String tok = fbody.get("_csrf");
                    LOG.warn("[admin] CSRF reject {} {}: cookiePresent={} formTok={}",
                            method, p, csrfCookiePresent,
                            tok == null ? "absent" : "len=" + tok.length());
                    return Optional.of(HttpReply.text(403, "Session expired. Reload and sign in again."));
                }
                return Optional.of(handlePlanePost(plane, body));
            }
            return Optional.of(planePage(who, plane));
        }

        if (p.equals("/admin") || p.equals("/admin/")) return Optional.of(dashboardPage(who));
        if (p.equals("/admin/ss7")) return Optional.of(HttpReply.redirect("/admin/ss7/outer"));
        if (p.equals("/admin/transit")) return Optional.of(transitPage(who));
        if (p.equals("/admin/status") || p.equals("/admin/status.json")) {
            return Optional.of(HttpReply.json(200, linkStatus.snapshot()));
        }
        if (p.equals("/admin/status/partial")) return Optional.of(HttpReply.html(linkStatus.htmlPartial()));
        if (p.equals("/admin/monitor-feed")) return Optional.of(HttpReply.json(200, monitorFeedMap()));

        LOG.info("[admin] unmatched route {} {}", method, p);
        return Optional.of(HttpReply.notFound());
    }

    private static boolean wantsShell(String method, String p) {
        return "GET".equalsIgnoreCase(method) && !p.endsWith(".json") && !p.contains("/partial");
    }

    private HttpReply handleLogin(String body) {
        Map<String, String> f = form(body);
        Optional<String> tok = adminAuth.login(f.get("username"), f.get("password"));
        if (tok.isEmpty()) {
            return loginPage("Invalid username or password");
        }
        String csrf = SignedSessionCookie.csrfToken(adminAuth.sessionHmacSecret(), tok.get());
        return HttpReply.redirect("/admin")
                .addSetCookie(SignedSessionCookie.setCookieHeader(tok.get(), cookieSecure))
                .addSetCookie(SignedSessionCookie.setCsrfCookieHeader(csrf, cookieSecure));
    }

    private Map<String, Object> monitorFeedMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("app", "stp");
        m.put("ts", System.currentTimeMillis());
        m.put("ss7.live", linkStatus.isM3uaRouteReady());
        m.put("links", linkStatus.snapshot());
        if (appTelemetry != null && appTelemetry.feedExtras() != null) {
            m.putAll(appTelemetry.feedExtras());
        }
        return m;
    }

    // ---- Monitor Hub (jainslee-monitor) ------------------------------------

    /**
     * Boot-time hub wiring ({@code StpBootstrap}). Binds the vendor SS7 admin
     * pack controls to STP semantics — Apply re-applies the current merged
     * {@code ss7.json}; Start/Stop delegate to {@link Ss7ApplyService}. Config
     * editing stays on this handler's own outer/inner plane pages.
     */
    public void wireRaAdminHub() {
        Ss7AdminBindings.bindHooks(
                this::applyMergedOrDirect, ss7Apply::start, ss7Apply::stop,
                null, this::liveSs7ConfigJson, null);
        this.monitorHub = buildHub();
        LOG.info("[admin] RA admin hub wired telemetry={} app={}",
                telemetryPort() != null, monitorAppName);
    }

    public void clearRaAdminHub() {
        Ss7AdminBindings.clearHooks();
        monitorHub = null;
    }

    private String applyMergedOrDirect() {
        if (Files.isRegularFile(planeStore.mergedPath())) {
            return ss7Apply.applyFile(planeStore.mergedPath());
        }
        return ss7Apply.apply();
    }

    /** Live merged stack document for the vendor pack config view (read-only). */
    private String liveSs7ConfigJson() {
        try {
            java.nio.file.Path merged = planeStore.mergedPath();
            if (merged != null && Files.isRegularFile(merged)) {
                return Files.readString(merged);
            }
        } catch (Exception ignored) {
            // fall through to "no config held"
        }
        return null;
    }

    private TelemetryPort telemetryPort() {
        return appTelemetry == null ? null : appTelemetry.port();
    }

    /** Lazy hub init so a Monitor Hub hit never 404s when wireRaAdminHub has not run yet. */
    private MonitorHandler monitor() {
        MonitorHandler h = monitorHub;
        if (h == null) {
            synchronized (this) {
                h = monitorHub;
                if (h == null) {
                    h = buildHub();
                    monitorHub = h;
                }
            }
        }
        return h;
    }

    /**
     * Build the hub registry explicitly. Quarkus fast-jar loads the root app jar
     * in a layer whose service resources are invisible to the TCCL ServiceLoader
     * scan at boot (vendor packs in {@code lib/main} win), so the STP KPI pack
     * must be appended by hand — merged with every pack the loaders do find,
     * deduped by raName (GMLC {@code buildHub} pattern).
     */
    private MonitorHandler buildHub() {
        LinkedHashMap<String, RaAdminDashboardContributor> byName = new LinkedHashMap<>();
        for (ClassLoader cl : new ClassLoader[] {
                Thread.currentThread().getContextClassLoader(),
                AdminDashboardRegistry.class.getClassLoader(),
                StpKpiContributor.class.getClassLoader()}) {
            if (cl == null) {
                continue;
            }
            try {
                for (RaAdminDashboardContributor c : java.util.ServiceLoader.load(
                        RaAdminDashboardContributor.class, cl)) {
                    if (c.manifest() != null && c.manifest().raName() != null) {
                        byName.putIfAbsent(c.manifest().raName(), c);
                    }
                }
            } catch (Throwable scanFailed) {
                LOG.warn("[admin] RA pack scan failed on {}: {}", cl, scanFailed.toString());
            }
        }
        StpKpiContributor kpi = new StpKpiContributor();
        byName.putIfAbsent(kpi.manifest().raName(), kpi);
        AdminDashboardRegistry registry = new AdminDashboardRegistry(byName.values());
        return new MonitorHandler(telemetryPort(), null, null, registry, monitorAppName);
    }

    /**
     * Paths the jainslee-monitor hub owns (see {@link MonitorHandler#handle}):
     * hub GUI's RA tab strip ({@code /api/admin/dashboards}), RA pack panels
     * ({@code /admin/ra/**}) and pack APIs ({@code /api/ra/**}) plus telemetry.
     */
    static boolean isMonitorHubPath(String path) {
        return path.equals("/telemetry") || path.startsWith("/telemetry/")
                || path.equals("/api/admin/dashboards")
                || path.startsWith("/admin/ra/")
                || path.startsWith("/api/ra/")
                || path.startsWith("/api/telemetry")
                || path.startsWith("/api/autonomous/");
    }

    /** Inert Monitor Hub assets only — never anything that renders live plane state. */
    private static final Set<String> PUBLIC_STATIC_EXTENSIONS = Set.of(
            ".js", ".css", ".svg", ".png", ".ico", ".map", ".woff", ".woff2");

    /** Anonymous GET allowlist for the hub, restricted to static asset extensions. */
    static boolean isPublicMonitorStatic(String method, String path) {
        if (method == null || (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method))) {
            return false;
        }
        if (path.startsWith("/telemetry/") || path.startsWith("/admin/ra/")) {
            int slash = path.lastIndexOf('/');
            int dot = path.lastIndexOf('.');
            return dot > slash && dot < path.length() - 1
                    && PUBLIC_STATIC_EXTENSIONS.contains(path.substring(dot).toLowerCase(Locale.ROOT));
        }
        return false;
    }

    // ---- pages -------------------------------------------------------------

    private HttpReply loginPage(String error) {
        return page("login.html", false, Map.of("{{ERROR}}",
                error == null ? "" : "<p class=\"text-rose-400 text-sm\">" + AdminPageRenderer.esc(error) + "</p>"));
    }

    private HttpReply dashboardPage(AdminAuthService.Principal who) {
        return page("index.html", true, Map.of());
    }

    private HttpReply planePage(AdminAuthService.Principal who, String plane) {
        boolean editable = who != null && who.isAdminOrOps();
        String title = Ss7PlaneStore.PLANE_OUTER.equals(plane)
                ? "SS7 OUTER — upper STP / HLR / MSC"
                : "SS7 INNER — USSD / SMSC / GMLC / silent-auth";
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("{{PLANE_NAME}}", AdminPageRenderer.esc(plane));
        vars.put("{{PLANE_TITLE}}", AdminPageRenderer.esc(title));
        vars.put("{{PLANE_STATUS_HTML}}", planeStatusTable(plane, title,
                Ss7PlaneStore.PLANE_OUTER.equals(plane)
                        ? "Northbound core links. Every link must be UP for the badge to stay LIVE."
                        : "Southbound service-cluster links attaching to the STP."));
        StringBuilder cards = new StringBuilder();
        if (editable) {
            for (Map<String, Object> c : planeStore.listConnections(plane)) {
                cards.append(connectionCardHtml(plane, c));
            }
            cards.append(connectionCardHtml(plane, Map.of()));
            vars.put("{{PLANE_CONNECTIONS}}", cards.toString());
            vars.put("{{SS7_ACTIONS}}", ss7ActionForm());
        } else {
            vars.put("{{PLANE_CONNECTIONS}}",
                    "<p class=\"text-sm text-ink-mute\">Sign in as ADMIN/OPS to edit connections.</p>");
            vars.put("{{SS7_ACTIONS}}", "");
        }
        return page(Ss7PlaneStore.PLANE_INNER.equals(plane) ? "ss7-inner.html" : "ss7-outer.html",
                true, vars);
    }

    private HttpReply transitPage(AdminAuthService.Principal who) {
        return page("transit.html", true, Map.of("{{TRANSIT_TABLE}}", transitTableHtml()));
    }

    // ---- template contract -------------------------------------------------

    private HttpReply page(String name, boolean loggedIn, Map<String, String> extra) {
        try {
            return pages.pageWith(name, nav.adminPageVars(loggedIn, extra));
        } catch (Exception e) {
            LOG.error("[admin] cannot render {}: {}", name, e.toString());
            return HttpReply.html(AdminPageRenderer.fallbackDashboard());
        }
    }

    private String ss7StatusHtml() {
        StringBuilder sb = new StringBuilder();
        sb.append(planeStatusTable("outer", "SS7 OUTER — upper STP / HLR / MSC",
                "Northbound core links. Every link must be UP for the outer badge to stay LIVE."));
        sb.append(planeStatusTable("inner", "SS7 INNER — USSD / SMSC / GMLC / silent-auth",
                "Southbound service-cluster links attaching to the STP."));
        StringBuilder rows = new StringBuilder();
        for (Map.Entry<String, Object> e : linkStatus.snapshot().entrySet()) {
            String k = e.getKey();
            if (k.startsWith("ss7.") || k.startsWith("sctp.") || k.startsWith("m3ua.") || k.startsWith("ha.")) {
                rows.append(schemaRow(k, String.valueOf(e.getValue())));
            }
        }
        sb.append(schemaTable(rows.toString()));
        return sb.toString();
    }

    /** One labeled per-plane status block: link rows with UP/DOWN badges + AS states. */
    private String planeStatusTable(String plane, String title, String description) {
        PlaneLinkStatus.PlaneView v = linkStatus.planeView(plane);
        boolean live = Boolean.TRUE.equals(v.keys().get("ss7." + plane + ".live"));
        String badge = "<span class=\"link-status-badge "
                + (live ? "link-status-badge--ok\">LIVE" : "link-status-badge--mute\">DOWN")
                + "</span>";
        StringBuilder rows = new StringBuilder();
        if (v.links().isEmpty() && v.ases().isEmpty()) {
            rows.append("<tr><td colspan=\"3\" class=\"px-3 py-2 text-sm text-ink-mute\">")
                    .append("No links configured in this plane yet.</td></tr>");
        }
        for (PlaneLinkStatus.LinkRow l : v.links()) {
            String st = l.up()
                    ? "<span class=\"link-status-badge link-status-badge--ok\">UP</span>"
                    : "<span class=\"link-status-badge link-status-badge--mute\">"
                            + AdminPageRenderer.esc(l.state()) + "</span>";
            rows.append("<tr><td class=\"px-3 py-2 font-mono text-slate-200\">")
                    .append(AdminPageRenderer.esc(l.name()))
                    .append("</td><td class=\"px-3 py-2 font-mono text-ink-mute\">")
                    .append(AdminPageRenderer.esc(l.peer() == null ? "" : l.peer()))
                    .append("</td><td class=\"px-3 py-2\">").append(st).append("</td></tr>");
        }
        for (PlaneLinkStatus.AsRow a : v.ases()) {
            String st = a.active()
                    ? "<span class=\"link-status-badge link-status-badge--ok\">" + AdminPageRenderer.esc(a.state()) + "</span>"
                    : "<span class=\"link-status-badge link-status-badge--mute\">" + AdminPageRenderer.esc(a.state()) + "</span>";
            rows.append("<tr><td class=\"px-3 py-2 font-mono text-slate-200\">")
                    .append(AdminPageRenderer.esc(a.name()))
                    .append("</td><td class=\"px-3 py-2 font-mono text-ink-mute\">M3UA AS</td>")
                    .append("<td class=\"px-3 py-2\">").append(st).append("</td></tr>");
        }
        return "<section id=\"" + AdminPageRenderer.esc(plane) + "\" aria-label=\""
                + AdminPageRenderer.esc(title) + "\" class=\"mt-8\">"
                + "<div class=\"flex items-center justify-between\">"
                + "<h3 class=\"text-lg font-semibold text-slate-50\">" + AdminPageRenderer.esc(title)
                + "</h3>" + badge + "</div>"
                + "<p class=\"mt-1 text-sm text-ink-mute\">" + AdminPageRenderer.esc(description)
                + " <span class=\"font-mono text-xs\">" + AdminPageRenderer.esc(
                        String.valueOf(v.keys().get("ss7." + plane + ".detail")))
                + "</span></p>"
                + "<div class=\"mt-2 rounded-lg border border-ink-line bg-ink-panel/80 p-2 overflow-x-auto\">"
                + "<table class=\"w-full text-left text-sm\"><thead class=\"border-b border-ink-line "
                + "text-xs uppercase tracking-wider text-ink-mute\"><tr>"
                + "<th class=\"px-3 py-2\">name</th><th class=\"px-3 py-2\">peer</th>"
                + "<th class=\"px-3 py-2\">state</th></tr></thead>"
                + "<tbody class=\"divide-y divide-ink-line/70\">" + rows + "</tbody></table></div>"
                + "</section>";
    }

    private String transitTableHtml() {
        StringBuilder rows = new StringBuilder();
        rows.append(schemaRow("transit.enabled",
                transit == null ? "?" : String.valueOf(transit.transitEnabled())));
        StpTransitConfig cfg = transit == null ? null : transit.config();
        if (cfg != null) {
            StpTransitConfig.Transit t = cfg.transit();
            StpTransitConfig.Acl a = cfg.acl();
            if (t != null) {
                rows.append(schemaRow("transit.removeSpc", String.valueOf(t.removeSpc())));
                rows.append(schemaRow("transit.maskGtInLogs", String.valueOf(t.maskGtInLogs())));
            }
            if (a != null) {
                rows.append(schemaRow("acl.defaultAction", a.defaultAction()));
                rows.append(schemaRow("acl.entries",
                        String.valueOf(a.entries() == null ? 0 : a.entries().size())));
            }
            if (cfg.ha() != null) {
                rows.append(schemaRow("ha.mode", cfg.ha().mode()));
                rows.append(schemaRow("ha.nodeId", cfg.ha().nodeId()));
            }
        }
        return schemaTable(rows.toString());
    }

    private static String schemaRow(String key, String value) {
        return "<tr><td class=\"px-3 py-2 font-mono text-ink-mute\">" + AdminPageRenderer.esc(key)
                + "</td><td class=\"px-3 py-2 font-mono text-slate-200\">" + AdminPageRenderer.esc(value)
                + "</td></tr>";
    }

    private static String schemaTable(String rows) {
        return "<table class=\"w-full text-left text-sm\">"
                + "<thead class=\"border-b border-ink-line text-xs uppercase tracking-wider text-ink-mute\">"
                + "<tr><th class=\"px-3 py-2\">key</th><th class=\"px-3 py-2\">value</th></tr></thead>"
                + "<tbody class=\"divide-y divide-ink-line/70\">" + rows + "</tbody></table>";
    }

    private HttpReply handleSs7Action(String body) {
        Map<String, String> f = form(body);
        switch (f.get("action") == null ? "" : f.get("action")) {
            case "apply" -> {
                if (java.nio.file.Files.isRegularFile(planeStore.mergedPath())) {
                    ss7Apply.applyFile(planeStore.mergedPath());
                } else {
                    ss7Apply.apply();
                }
            }
            case "start" -> ss7Apply.start();
            case "stop" -> ss7Apply.stop();
            case "save" -> {
                Ss7PlaneStore.Result r = planeStore.savePlane(f.get("plane"), f.get("json"));
                if (!r.ok()) {
                    return HttpReply.text(400, r.message());
                }
                try {
                    ss7Apply.applyFile(planeStore.mergedPath());
                } catch (RuntimeException ex) {
                    return HttpReply.text(500, "saved, but apply failed: " + ex.getMessage());
                }
            }
            default -> { return HttpReply.text(400, "unknown-action"); }
        }
        return HttpReply.redirect("/admin/ss7");
    }

    /** One POST per card: upserts/deletes the connection into the plane fragment and re-applies. */
    private HttpReply handlePlanePost(String plane, String body) {
        Map<String, String> f = form(body);
        switch (f.getOrDefault("action", "")) {
            case "save-connection" -> {
                Ss7PlaneStore.ConnectionSpec spec = new Ss7PlaneStore.ConnectionSpec(
                        trim(f.get("name")),
                        "sctp",
                        trim(f.get("localHost")),
                        parseIntOr(f.get("localPort"), 0),
                        trim(f.get("peerHost")),
                        parseIntOr(f.get("peerPort"), 0),
                        "AS-" + trim(f.get("name")),
                        orDefault(f.get("asMode"), "loadshare"),
                        parseIntOr(f.get("routingContext"), 0),
                        parseIntOr(f.get("dpc"), -1),
                        f.get("opc") == null || f.get("opc").isBlank()
                                ? null : parseIntOr(f.get("opc"), 0),
                        parseIntOr(f.get("networkId"), 0),
                        trim(f.get("gttPattern")),
                        f.get("gttToSsn") == null || f.get("gttToSsn").isBlank()
                                ? null : parseIntOr(f.get("gttToSsn"), 8),
                        trim(f.get("old_name")));
                Ss7PlaneStore.Result r = planeStore.saveConnection(plane, spec);
                if (!r.ok()) return HttpReply.text(400, r.message());
                return applyMergedAndRedirect(plane);
            }
            case "delete-connection" -> {
                Ss7PlaneStore.Result r = planeStore.deleteConnection(plane, trim(f.get("name")));
                if (!r.ok()) return HttpReply.text(400, r.message());
                return applyMergedAndRedirect(plane);
            }
            default -> { return handleSs7Action(body); }
        }
    }

    /** Merge is persisted by save/delete; re-apply the stack then reload that plane page. */
    private HttpReply applyMergedAndRedirect(String plane) {
        try {
            if (Files.isRegularFile(planeStore.mergedPath())) {
                ss7Apply.applyFile(planeStore.mergedPath());
            } else {
                ss7Apply.apply();
            }
            return HttpReply.redirect("/admin/ss7/" + plane);
        } catch (RuntimeException ex) {
            return HttpReply.text(500, "saved, but apply failed: " + ex.getMessage());
        }
    }

    private static String trim(String s) { return s == null ? null : s.trim(); }

    private static int parseIntOr(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static String orDefault(String s, String def) {
        return s == null || s.isBlank() ? def : s;
    }

    /**
     * Session CSRF check: session principals must echo the signed csrf token
     * (header {@code X-STP-CSRF} or form field {@code _csrf}); API-key
     * principals are already header-authenticated and skip the check.
     */
    private static boolean sessionCsrfOk(AdminAuthService.Principal who,
                                         Map<String, String> headers, String body) {
        if (who == null || !who.fromSession()) return true;
        String cookieHeader = headers == null ? null : headers.get("Cookie");
        String csrfCookie = SignedSessionCookie
                .extractCookie(cookieHeader, SignedSessionCookie.CSRF_COOKIE_NAME).orElse(null);
        String csrfHeader = headers == null ? null : headers.get(SignedSessionCookie.CSRF_HEADER);
        if (csrfHeader != null) {
            return SignedSessionCookie.csrfMatches(csrfCookie, csrfHeader);
        }
        String formCsrf = form(body).get("_csrf");
        return formCsrf != null && SignedSessionCookie.csrfMatches(csrfCookie, formCsrf);
    }

    private String ss7PlanesHtml() {
        return planePanel(
                "SS7 outer — upper STP / HLR / MSC",
                "Northbound links to the core network. GT rules resolve caller GTs toward HLR/MSC via each provider's GT.",
                Ss7PlaneStore.PLANE_OUTER,
                planeStore.plane(Ss7PlaneStore.PLANE_OUTER),
                Files.isRegularFile(java.nio.file.Path.of(Ss7PlaneStore.OUTER_FILE))
                        ? null
                        : "First save seeds this editor from the live configs/ss7.json — prune the inner-plane entries before saving, or the merge will duplicate them.")
                + planePanel(
                "SS7 inner — GMLC / USSD / SMSC / OTA / silent-auth",
                "Southbound application servers attaching to the STP. Each node connects N-N; routing is GT-based with networkId + tenantId.",
                Ss7PlaneStore.PLANE_INNER,
                planeStore.plane(Ss7PlaneStore.PLANE_INNER),
                null);
    }

    private String planePanel(String title, String description, String plane, String json,
                              String warning) {
        String planeLabel = Ss7PlaneStore.PLANE_OUTER.equals(plane) ? "Outer plane" : "Inner plane";
        String warnHtml = warning == null ? ""
                : "<p class=\"mt-2 rounded-md border border-amber-500/40 bg-amber-500/10 px-3 py-2 text-xs text-amber-200\">"
                        + AdminPageRenderer.esc(warning) + "</p>";
        return "<section id=\"edit-" + AdminPageRenderer.esc(plane)
                + "\" aria-label=\"" + AdminPageRenderer.esc(title) + "\">"
                + "<p class=\"text-xs uppercase tracking-[0.25em] text-signal\">" + planeLabel + "</p>"
                + "<h3 class=\"mt-1 text-xl font-semibold text-slate-50\">" + AdminPageRenderer.esc(title)
                + "</h3>"
                + "<p class=\"mt-1 text-sm text-ink-mute\">" + AdminPageRenderer.esc(description) + "</p>"
                + warnHtml
                + "<form method=\"post\" action=\"/admin/ss7\" class=\"mt-3 rounded-lg border border-ink-line bg-ink-panel/80 p-4\">"
                + "<input type=\"hidden\" name=\"_csrf\"/>"
                + "<input type=\"hidden\" name=\"action\" value=\"save\"/>"
                + "<input type=\"hidden\" name=\"plane\" value=\"" + AdminPageRenderer.esc(plane) + "\"/>"
                + "<textarea name=\"json\" rows=\"22\" spellcheck=\"false\" aria-label=\""
                + AdminPageRenderer.esc(title) + " JSON"
                + "\" class=\"block w-full resize-y rounded-md border border-ink-line bg-ink px-3 py-2 font-mono text-xs leading-relaxed text-slate-200 focus:border-signal focus:outline-none\">"
                + AdminPageRenderer.esc(json)
                + "</textarea>"
                + "<div class=\"mt-3 flex flex-wrap items-center justify-between gap-3\">"
                + "<p class=\"text-xs text-ink-mute\">Saved independently, then auto-merged into <code class=\"font-mono text-slate-300\">configs/ss7.json</code> and re-applied.</p>"
                + "<button type=\"submit\" class=\"rounded-md border border-signal px-4 py-2 text-sm font-medium text-signal hover:bg-signal hover:text-ink\">Save "
                + AdminPageRenderer.esc(plane) + "</button>"
                + "</div></form></section>";
    }

    private static String ss7ActionForm() {
        return "<section class=\"mt-8\" aria-label=\"SS7 actions\">"
                + "<p class=\"text-xs uppercase tracking-[0.25em] text-signal\">Actions</p>"
                + "<form method=\"post\" action=\"/admin/ss7\" class=\"mt-3 flex flex-wrap gap-3\">"
                + "<input type=\"hidden\" name=\"_csrf\"/>"
                + "<button type=\"submit\" name=\"action\" value=\"apply\""
                + " class=\"rounded-md border border-ink-line px-4 py-2 text-sm text-slate-200 hover:border-signal hover:text-signal\">Apply</button>"
                + "<button type=\"submit\" name=\"action\" value=\"start\""
                + " class=\"rounded-md border border-ink-line px-4 py-2 text-sm text-slate-200 hover:border-signal hover:text-signal\">Start</button>"
                + "<button type=\"submit\" name=\"action\" value=\"stop\""
                + " class=\"rounded-md border border-rose-500/50 px-4 py-2 text-sm text-rose-200 hover:border-rose-400\">Stop</button>"
                + "</form></section>";
    }

    /**
     * Renders one editable connection card — every connection is its own div
     * with its own Save button; saving upserts into the plane fragment,
     * re-merges configs/ss7.json and re-applies the stack, then reloads.
     */
    private String connectionCardHtml(String plane, Map<String, Object> c) {
        boolean isNew = c.isEmpty();
        String name = str(c.get("name"), "");
        String title = isNew ? "Add connection" : "Connection: " + name;

        StringBuilder g = new StringBuilder();
        g.append(field(isNew ? "Link name *" : "Link name", "name", name, "L-C", isNew));
        g.append(selectField("AS mode", "asMode", str(c.get("asMode"), "loadshare")));
        g.append(field("Routing context", "routingContext", str(c.get("routingContext"), "0"), "0", false));
        g.append(field("Local host", "localHost", str(c.get("localHost"), "127.0.0.1"), "127.0.0.1", false));
        g.append(field("Local port", "localPort", str(c.get("localPort"), ""), "8025", false));
        g.append(field("Peer host", "peerHost", str(c.get("peerHost"), "127.0.0.1"), "127.0.0.1", false));
        g.append(field("Peer port", "peerPort", str(c.get("peerPort"), ""), "8026", false));
        g.append(field("Route DPC *", "dpc", str(c.get("dpc"), ""), "203", false));
        g.append(field("networkId", "networkId", str(c.get("networkId"), "0"), "0", false));
        g.append(field("GTT pattern (optional)", "gttPattern", str(c.get("gttPattern"), ""), "2519/*", false));
        g.append(field("GTT → SSN", "gttToSsn", str(c.get("gttToSsn"), ""), "251", false));

        return "<section class=\"mt-4 rounded-lg border border-ink-line bg-ink-panel/80 p-4\""
                + " aria-label=\"" + AdminPageRenderer.esc(title) + "\">"
                + "<div class=\"flex items-center justify-between\">"
                + "<h4 class=\"font-semibold text-slate-100\">" + AdminPageRenderer.esc(title) + "</h4>"
                + "<span class=\"text-xs text-ink-mute\">link · AS · route · GTT(networkId)</span>"
                + "</div>"
                + "<form method=\"post\" action=\"/admin/ss7/" + plane
                + "\" autocomplete=\"off\" class=\"mt-3 grid gap-3 sm:grid-cols-2 lg:grid-cols-3\">"
                + "<input type=\"hidden\" name=\"_csrf\"/>"
                + "<input type=\"hidden\" name=\"action\" value=\"save-connection\"/>"
                + "<input type=\"hidden\" name=\"old_name\" value=\"" + AdminPageRenderer.esc(name) + "\"/>"
                + g
                + "<div class=\"flex items-end gap-2 sm:col-span-2 lg:col-span-3\">"
                + "<button type=\"submit\" class=\"rounded-md border border-signal px-4 py-2 text-sm font-medium text-signal hover:bg-signal hover:text-ink\">Save "
                + (isNew ? "connection" : AdminPageRenderer.esc(name)) + "</button>"
                + "</div></form>"
                + (isNew ? ""
                        : "<form method=\"post\" action=\"/admin/ss7/" + plane + "\" class=\"mt-2\">"
                                + "<input type=\"hidden\" name=\"_csrf\"/>"
                                + "<input type=\"hidden\" name=\"action\" value=\"delete-connection\"/>"
                                + "<input type=\"hidden\" name=\"name\" value=\"" + AdminPageRenderer.esc(name) + "\"/>"
                                + "<button type=\"submit\" class=\"rounded-md border border-rose-500/50 px-3 py-1.5 text-xs text-rose-200 hover:border-rose-400\">Delete "
                                + AdminPageRenderer.esc(name) + "</button></form>")
                + "</section>";
    }

    private static String field(String label, String name, String value, String placeholder,
                                boolean readonly) {
        String ro = readonly ? " readonly" : "";
        return "<label class=\"text-xs text-ink-mute\">" + AdminPageRenderer.esc(label)
                + "<input name=\"" + AdminPageRenderer.esc(name) + "\" value=\""
                + AdminPageRenderer.esc(value == null ? "" : value)
                + "\" placeholder=\"" + AdminPageRenderer.esc(placeholder == null ? "" : placeholder)
                + "\"" + ro
                + " class=\"mt-1 block w-full rounded-md border border-ink-line bg-ink px-2 py-1.5 font-mono text-xs text-slate-200 focus:border-signal focus:outline-none\"/></label>";
    }

    private static String selectField(String label, String name, String value) {
        return "<label class=\"text-xs text-ink-mute\">" + AdminPageRenderer.esc(label)
                + "<select name=\"" + AdminPageRenderer.esc(name)
                + "\" class=\"mt-1 block w-full rounded-md border border-ink-line bg-ink px-2 py-1.5 font-mono text-xs text-slate-200 focus:border-signal focus:outline-none\">"
                + option("loadshare", value) + option("override", value)
                + "</select></label>";
    }

    private static String option(String v, String current) {
        boolean selected = v.equals(current);
        return "<option value=\"" + v + "\"" + (selected ? " selected" : "") + ">" + v + "</option>";
    }

    private static String str(Object o, String def) {
        return o == null ? def : String.valueOf(o);
    }

    /** Decode a minimal x-www-form-urlencoded body ({@code k=v&k=v&...}). */
    static Map<String, String> form(String body) {
        Map<String, String> out = new LinkedHashMap<>();
        if (body == null || body.isBlank()) return out;
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                out.put(urlDecode(pair), "");
            } else {
                out.put(urlDecode(pair.substring(0, eq)), urlDecode(pair.substring(eq + 1)));
            }
        }
        return out;
    }

    private static String urlDecode(String s) {
        try {
            return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return s;
        }
    }

    /** Immutable HTTP reply value; factories cover the renderer's needs. */
    public record HttpReply(int status, String contentType, byte[] body, Map<String, String> headers) {
        public static final String SET_COOKIE_SEP = "\n";

        public static HttpReply html(String html) {
            return html(200, html);
        }

        public static HttpReply html(int status, String html) {
            return new HttpReply(status, "text/html; charset=utf-8",
                    html.getBytes(StandardCharsets.UTF_8), Map.of());
        }

        public static HttpReply json(int status, byte[] body) {
            return new HttpReply(status, "application/json", body == null ? new byte[0] : body, Map.of());
        }

        public static HttpReply json(int status, Object node) {
            try {
                return new HttpReply(status, "application/json", JSON.writeValueAsBytes(node), Map.of());
            } catch (Exception e) {
                return text(500, "serialize");
            }
        }

        public static HttpReply text(int status, String body) {
            return new HttpReply(status, "text/plain; charset=utf-8",
                    body.getBytes(StandardCharsets.UTF_8), Map.of());
        }

        public static HttpReply bytes(String contentType, byte[] body) {
            return new HttpReply(200, contentType, body == null ? new byte[0] : body, Map.of());
        }

        public static HttpReply notFound() {
            return text(404, "not found");
        }

        public static HttpReply redirect(String location) {
            return new HttpReply(302, "text/plain; charset=utf-8",
                    ("Redirect: " + location).getBytes(StandardCharsets.UTF_8),
                    Map.of("Location", location));
        }

        public HttpReply addSetCookie(String cookie) {
            Map<String, String> h = new LinkedHashMap<>(headers == null ? Map.of() : headers);
            String existing = h.get("Set-Cookie");
            h.put("Set-Cookie", existing == null ? cookie : existing + SET_COOKIE_SEP + cookie);
            return new HttpReply(status, contentType, body, Map.copyOf(h));
        }
    }
}
