package et.elisa.stp.admin;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.LinkedHashMap;
import java.util.Map;

@ApplicationScoped
public class AdminNavRenderer {
    public static String adminNavLinks(boolean loggedIn) {
        String auth = loggedIn
                ? "<a class=\"hover:text-signal\" href=\"/admin/logout\">Logout</a>"
                : "<a class=\"hover:text-signal\" href=\"/admin/login\">Login</a>";
        return """
                <a class="hover:text-signal" href="/admin">Dashboard</a>
                <a class="hover:text-signal" href="/admin/ss7/outer">SS7 Outer</a>
                <a class="hover:text-signal" href="/admin/ss7/inner">SS7 Inner</a>
                <a class="hover:text-signal" href="/admin/transit">Transit</a>
                <a class="hover:text-signal" href="/admin/diameter">Diameter</a>
                <a class="hover:text-signal" href="/telemetry/">Monitor Hub</a>
                %s
                """.formatted(auth).trim();
    }

    public Map<String, String> adminPageVars(boolean loggedIn, Map<String, String> extra) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("{{NAV_LINKS}}", adminNavLinks(loggedIn));
        m.put("{{NOTICE}}", "");
        m.put("{{ERROR}}", "");
        m.put("{{SUCCESS_BANNER}}", "");
        m.put("{{MONITOR_STRIP}}", "");
        if (extra != null) m.putAll(extra);
        return m;
    }
}
