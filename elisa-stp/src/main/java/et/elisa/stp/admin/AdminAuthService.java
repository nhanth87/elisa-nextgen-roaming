package et.elisa.stp.admin;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Cookie-session + API-key authentication for the STP admin dashboard.
 * Simplified from gmlc-microjainslee: single seeded admin user, no tenants.
 */
@ApplicationScoped
public class AdminAuthService {
    private static final Logger LOG = LogManager.getLogger(AdminAuthService.class);
    public static final String ADMIN_KEY_HEADER = "X-STP-Admin-Key";

    public record Principal(String role, String username, boolean fromSession) {
        public boolean isAdminOrOps() {
            return "ADMIN".equals(role) || "OPS".equals(role);
        }
    }

    @ConfigProperty(name = "stp.admin.session-hmac-secret",
            defaultValue = "stp-dev-session-hmac-secret-change-me")
    String sessionHmacSecret;
    @ConfigProperty(name = "stp.admin.api-key", defaultValue = "stp-admin")
    String adminApiKey;
    @ConfigProperty(name = "stp.admin.username", defaultValue = "admin")
    String adminUsername;
    @ConfigProperty(name = "stp.admin.first-run-password", defaultValue = "stp-admin")
    String firstRunPassword;

    public Optional<Principal> authenticate(Map<String, String> headers, Map<String, String> query) {
        try {
            Optional<String> cookieTok = SignedSessionCookie.extractFromCookieHeader(
                    header(headers, "Cookie"));
            if (cookieTok.isPresent()) {
                Optional<SignedSessionCookie.Claims> claims =
                        SignedSessionCookie.verify(sessionHmacSecret, cookieTok.get());
                if (claims.isPresent()) {
                    SignedSessionCookie.Claims c = claims.get();
                    return Optional.of(new Principal(c.role(), c.username(), true));
                }
            }
            String key = header(headers, ADMIN_KEY_HEADER);
            if (key != null && key.trim().equals(adminApiKey)) {
                return Optional.of(new Principal("ADMIN", "api-key", false));
            }
            return Optional.empty();
        } catch (RuntimeException ex) {
            LOG.error("[admin] authentication aborted: {}", ex.toString());
            return Optional.empty();
        }
    }

    public Optional<String> login(String username, String password) {
        if (username == null || password == null) return Optional.empty();
        if (!username.trim().equals(adminUsername) || !password.equals(firstRunPassword)) {
            return Optional.empty();
        }
        Instant exp = Instant.now().plus(1, ChronoUnit.DAYS);
        return Optional.of(SignedSessionCookie.issue(sessionHmacSecret, username.trim(), "ADMIN", null, exp));
    }

    public String sessionHmacSecret() { return sessionHmacSecret; }
    public boolean adminKeyOk(String key) { return key != null && key.trim().equals(adminApiKey); }

    static String header(Map<String, String> headers, String name) {
        if (headers == null || name == null) return null;
        String v = headers.get(name);
        if (v != null) return v;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }
}
