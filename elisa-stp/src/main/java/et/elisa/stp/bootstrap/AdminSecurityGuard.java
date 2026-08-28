package et.elisa.stp.bootstrap;

import io.quarkus.runtime.StartupEvent;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Fail-fast boot guard (B5): refuses to start with placeholder admin
 * credentials when the Quarkus profile is not dev/test. Runs at
 * {@code @Priority(10)} — ahead of {@link StpClusterBootstrap} (20) and
 * {@link StpBootstrap} — so a misconfigured prod boot dies before any RA
 * activates or any listener binds.
 */
@ApplicationScoped
public class AdminSecurityGuard {
    private static final Logger LOG = LogManager.getLogger(AdminSecurityGuard.class);
    private static final String HMAC_PROP = "stp.admin.session-hmac-secret";
    private static final String API_KEY_PROP = "stp.admin.api-key";
    private static final String USER_PROP = "stp.admin.username";
    private static final String PASS_PROP = "stp.admin.first-run-password";
    private static final String DEV_HMAC = "stp-dev-session-hmac-secret-change-me";
    private static final String DEV_API_KEY = "stp-admin";
    private static final String DEV_USER = "admin";
    private static final String DEV_PASS = "stp-admin";

    @ConfigProperty(name = "quarkus.profile")
    String profile;
    @ConfigProperty(name = HMAC_PROP, defaultValue = DEV_HMAC)
    String sessionHmacSecret;
    @ConfigProperty(name = API_KEY_PROP, defaultValue = DEV_API_KEY)
    String adminApiKey;
    @ConfigProperty(name = USER_PROP, defaultValue = DEV_USER)
    String adminUsername;
    @ConfigProperty(name = PASS_PROP, defaultValue = DEV_PASS)
    String firstRunPassword;

    void onStart(@Observes @Priority(10) StartupEvent ev) {
        List<String> problems = check(profile, sessionHmacSecret, adminApiKey,
                adminUsername, firstRunPassword);
        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "STP admin credentials still use placeholder defaults in profile '"
                            + profile + "': " + String.join(", ", problems)
                            + ". Set real values in configs/application.properties before prod boot.");
        }
        LOG.info("STP admin security guard OK (profile={})", profile);
    }

    /** @return the offending property names; empty when the standby config is acceptable. */
    static List<String> check(String profile, String hmac, String apiKey,
                              String username, String password) {
        List<String> problems = new ArrayList<>();
        if (!isSensitive(profile)) return problems;
        if (DEV_HMAC.equals(hmac)) problems.add(HMAC_PROP);
        if (DEV_API_KEY.equals(apiKey)) problems.add(API_KEY_PROP);
        if (DEV_USER.equals(username)) problems.add(USER_PROP);
        if (DEV_PASS.equals(password)) problems.add(PASS_PROP);
        return problems;
    }

    private static boolean isSensitive(String profile) {
        String p = profile == null ? "" : profile.trim().toLowerCase(Locale.ROOT);
        return !p.equals("dev") && !p.equals("test");
    }
}