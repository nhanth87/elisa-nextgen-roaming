package et.elisa.iwf.admin;

import et.elisa.iwf.IwfConfig;
import et.elisa.iwf.IwfConfigJson;
import et.elisa.iwf.bootstrap.IwfBootstrap;
import et.elisa.iwf.map.NoopMapLeg;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@ApplicationScoped
public class IwfAdminPageHandler {
    private static final Logger LOG = LogManager.getLogger(IwfAdminPageHandler.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Inject AdminPageRenderer pages;
    @Inject AdminNavRenderer nav;
    @Inject LinkStatusService linkStatus;
    @Inject IwfBootstrap bootstrap;

    public record HttpReply(int status, String contentType, byte[] body) {
        public static HttpReply html(String html) {
            return html(200, html);
        }
        public static HttpReply html(int status, String html) {
            return new HttpReply(status, "text/html; charset=utf-8",
                    html.getBytes(StandardCharsets.UTF_8));
        }
        public static HttpReply json(int status, Object node) {
            try {
                return new HttpReply(status, "application/json",
                        JSON.writeValueAsBytes(node));
            } catch (Exception e) {
                return text(500, "serialize");
            }
        }
        public static HttpReply text(int status, String body) {
            return new HttpReply(status, "text/plain; charset=utf-8",
                    body.getBytes(StandardCharsets.UTF_8));
        }
        public static HttpReply bytes(String contentType, byte[] body) {
            return new HttpReply(200, contentType, body == null ? new byte[0] : body);
        }
        public static HttpReply notFound() {
            return text(404, "not found");
        }
        public static HttpReply redirect(String location) {
            return new HttpReply(302, "text/plain; charset=utf-8",
                    ("Redirect: " + location).getBytes(StandardCharsets.UTF_8));
        }
    }

    public Optional<HttpReply> tryHandle(String method, String path,
                                         Map<String, String> query, String body) {
        if (path == null) return Optional.empty();
        String p = path.startsWith("/") ? path : "/" + path;
        int qmark = p.indexOf('?');
        if (qmark >= 0) p = p.substring(0, qmark);

        if (p.startsWith("/admin/static/")) {
            return handleStatic(p.substring("/admin/static/".length()));
        }
        if ("GET".equalsIgnoreCase(method)) {
            return handleGet(p);
        }
        if ("POST".equalsIgnoreCase(method)) {
            return handlePost(p, body);
        }
        return Optional.empty();
    }

    private Optional<HttpReply> handleGet(String p) {
        if ("/".equals(p) || "/admin".equals(p) || "/admin/".equals(p)) {
            return servePage("index.html", Map.of());
        }
        if ("/admin/ss7".equals(p)) {
            return servePage("ss7.html", ss7Vars());
        }
        if ("/admin/diameter".equals(p)) {
            return servePage("diameter.html", diameterVars());
        }
        if ("/admin/routing".equals(p)) {
            return servePage("routing.html", routingVars());
        }
        if ("/admin/telemetry".equals(p)) {
            return servePage("telemetry.html", Map.of());
        }
        return Optional.empty();
    }

    private Optional<HttpReply> handlePost(String p, String body) {
        if ("/admin/ss7".equals(p)) {
            return handleSs7Post(body);
        }
        if ("/admin/diameter".equals(p)) {
            return handleDiameterPost(body);
        }
        return Optional.empty();
    }

    private Map<String, String> ss7Vars() {
        IwfConfig c = IwfConfigJson.load();
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("active", !(bootstrap.mapLeg() instanceof NoopMapLeg));
        status.put("ssn", c.map().ssn());
        status.put("gt", c.map().ownGt());
        status.put("spc", c.map().ownSpc());

        Map<String, String> vars = nav.adminPageVars(Map.of(
                "{{MAP_STATUS_HTML}}", AdminIwfRenderer.mapStatusHtml(status),
                "{{MAP_SSN}}", String.valueOf(c.map().ssn()),
                "{{MAP_GT}}", AdminPageRenderer.esc(c.map().ownGt()),
                "{{MAP_SPC}}", AdminPageRenderer.esc(c.map().ownSpc())));
        return vars;
    }

    private Map<String, String> diameterVars() {
        IwfConfig c = IwfConfigJson.load();
        var leg = bootstrap.diameterLeg();
        Map<String, Object> status = leg instanceof et.elisa.iwf.diameter.CorsacDiameterLeg corsac
                ? corsac.healthSnapshot() : Map.of("open", false, "detail", "Diameter leg not active");

        Map<String, String> vars = nav.adminPageVars(Map.of(
                "{{DIA_STATUS_HTML}}", AdminIwfRenderer.diameterStatusHtml(status),
                "{{DIA_DRA_HOST}}", AdminPageRenderer.esc(c.diameter().draHost()),
                "{{DIA_DRA_PORT}}", String.valueOf(c.diameter().draPort()),
                "{{DIA_SRC_PORT}}", String.valueOf(c.diameter().srcPort()),
                "{{DIA_ORIGIN_HOST}}", AdminPageRenderer.esc(c.diameter().originHost()),
                "{{DIA_ORIGIN_REALM}}", AdminPageRenderer.esc(c.diameter().originRealm()),
                "{{DIA_DEST_HOST}}", AdminPageRenderer.esc(c.diameter().destHost()),
                "{{DIA_DEST_REALM}}", AdminPageRenderer.esc(c.diameter().destRealm()),
                "{{DIA_TIMEOUT}}", String.valueOf(c.diameter().responseTimeoutMillis())));
        return vars;
    }

    private Map<String, String> routingVars() {
        Map<String, String> vars = nav.adminPageVars(Map.of(
                "{{ROUTING_TABLE}}", AdminIwfRenderer.routingTableHtml()));
        return vars;
    }

    private Optional<HttpReply> servePage(String name, Map<String, String> vars) {
        try {
            byte[] html = pages.render(name, vars);
            if (html == null) return Optional.of(HttpReply.notFound());
            return Optional.of(new HttpReply(200, "text/html; charset=utf-8", html));
        } catch (Exception e) {
            LOG.error("[admin] cannot render {}: {}", name, e.toString());
            return Optional.of(HttpReply.html(500, fallbackDashboard()));
        }
    }

    private Optional<HttpReply> handleStatic(String rest) {
        try {
            byte[] raw = pages.staticResource(rest);
            if (raw == null) return Optional.of(HttpReply.notFound());
            return Optional.of(new HttpReply(200, pages.staticContentType(rest), raw));
        } catch (Exception e) {
            return Optional.of(HttpReply.notFound());
        }
    }

    private Optional<HttpReply> handleSs7Post(String body) {
        Map<String, String> fields = form(body);
        String action = fields.getOrDefault("action", "");
        if ("apply".equals(action)) {
            // TODO: start/stop MAP leg when ra-jss7 wired (M-IWF-3)
            LOG.info("[admin] SS7 apply requested — MAP leg not yet active");
        }
        return Optional.of(HttpReply.redirect("/admin/ss7"));
    }

    private Optional<HttpReply> handleDiameterPost(String body) {
        Map<String, String> fields = form(body);
        String action = fields.getOrDefault("action", "");
        if ("save".equals(action) || "saveApply".equals(action)) {
            String json = fields.getOrDefault("configJson", "");
            try {
                IwfConfig parsed = IwfConfigJson.parse(json);
                saveConfig(parsed);
            } catch (Exception e) {
                return Optional.of(HttpReply.text(400, "Invalid config: " + e.getMessage()));
            }
        }
        if ("saveApply".equals(action)) {
            // TODO: re-init Diameter leg with new config
            LOG.info("[admin] Diameter save+apply requested");
        }
        return Optional.of(HttpReply.redirect("/admin/diameter"));
    }

    private void saveConfig(IwfConfig config) throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        Object node = mapper.valueToTree(IwfConfig.asMap(config));
        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        Path target = Path.of("configs/iwf.json");
        Files.createDirectories(target.getParent());
        Files.writeString(target, json);
        LOG.info("[admin] config saved to {}", target);
    }

    static Map<String, String> form(String body) {
        Map<String, String> out = new LinkedHashMap<>();
        if (body == null || body.isBlank()) return out;
        for (String part : body.split("&")) {
            int eq = part.indexOf('=');
            if (eq < 0) continue;
            out.put(java.net.URLDecoder.decode(part.substring(0, eq), StandardCharsets.UTF_8),
                    java.net.URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    private static String fallbackDashboard() {
        return "<!DOCTYPE html><html><head><meta charset=utf-8/><title>Elisa IWF</title></head>"
                + "<body><h1>Elisa IWF</h1><ul>"
                + "<li><a href=/admin/diameter>Diameter</a></li>"
                + "<li><a href=/admin/ss7>JSS7</a></li>"
                + "<li><a href=/admin/routing>Routing</a></li>"
                + "<li><a href=/admin/telemetry>Telemetry</a></li></ul></body></html>";
    }
}
