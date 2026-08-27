package et.elisa.iwf.admin;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.LinkedHashMap;
import java.util.Map;

@ApplicationScoped
public class AdminNavRenderer {

    public static String adminNavLinks() {
        return """
                <a class="hover:text-signal" href="/admin/">Dashboard</a>
                <a class="hover:text-signal" href="/admin/ss7">JSS7</a>
                <a class="hover:text-signal" href="/admin/diameter">Diameter</a>
                <a class="hover:text-signal" href="/admin/routing">Routing</a>
                <a class="hover:text-signal" href="/admin/telemetry">Telemetry</a>
                """;
    }

    public Map<String, String> adminPageVars(Map<String, String> extra) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("{{NAV_LINKS}}", adminNavLinks());
        m.put("{{SUCCESS_BANNER}}", "");
        m.put("{{NOTICE}}", "");
        m.put("{{ERROR}}", "");
        if (extra != null) m.putAll(extra);
        return m;
    }
}
