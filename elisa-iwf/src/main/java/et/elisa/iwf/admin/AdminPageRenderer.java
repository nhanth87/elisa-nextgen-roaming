package et.elisa.iwf.admin;

import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class AdminPageRenderer {
    private static final Logger LOG = LogManager.getLogger(AdminPageRenderer.class);
    private static final AtomicBoolean LOGGED_ROOT = new AtomicBoolean();
    private static final Pattern TEMPLATE_TOKEN = Pattern.compile("\\{\\{[A-Z][A-Z0-9_]*\\}\\}");

    @ConfigProperty(name = "iwf.admin.ui-dir", defaultValue = "app/html")
    String uiDir;

    public byte[] render(String name, Map<String, String> vars) throws Exception {
        byte[] raw = readFile(uiRoot(), "admin/" + name);
        if (raw == null) return null;
        return applyTemplateVars(name, new String(raw, StandardCharsets.UTF_8), vars)
                .getBytes(StandardCharsets.UTF_8);
    }

    public byte[] staticResource(String rest) throws Exception {
        Path root = uiRoot();
        byte[] raw = readFile(root, "admin/static/" + rest);
        if (raw == null) return null;
        return raw;
    }

    public String staticContentType(String rest) {
        if (rest.endsWith(".css")) return "text/css";
        if (rest.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (rest.endsWith(".html")) return "text/html; charset=utf-8";
        if (rest.endsWith(".png")) return "image/png";
        if (rest.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }

    static String applyTemplateVars(String templateName, String html, Map<String, String> vars) {
        if (html == null) return "";
        String out = html;
        if (vars != null) {
            for (var entry : vars.entrySet()) {
                if (entry.getKey() == null) continue;
                out = out.replace(entry.getKey(), entry.getValue() == null ? "" : entry.getValue());
            }
        }
        Matcher leftover = TEMPLATE_TOKEN.matcher(out);
        if (leftover.find()) {
            LOG.warn("[admin] unsubstituted tokens in {} — stripping", templateName);
            out = leftover.replaceAll("");
        }
        return out;
    }

    public static String esc(Object o) {
        if (o == null) return "";
        return o.toString().replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    Path uiRoot() {
        Path root = resolveUiRoot(uiDir);
        if (LOGGED_ROOT.compareAndSet(false, true)) {
            LOG.info("[admin] UI directory: {}", root);
        }
        return root;
    }

    static Path resolveUiRoot(String configured) {
        String cfg = configured == null || configured.isBlank() ? "app/html" : configured.trim();
        Path p = Path.of(cfg);
        if (!p.isAbsolute()) p = Path.of(System.getProperty("user.dir", ".")).resolve(p);
        return p.toAbsolutePath().normalize();
    }

    static byte[] readFile(Path root, String relative) throws IOException {
        if (relative == null || relative.isBlank() || relative.contains("..")) return null;
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root) || !Files.isRegularFile(resolved)) return null;
        return Files.readAllBytes(resolved);
    }
}
