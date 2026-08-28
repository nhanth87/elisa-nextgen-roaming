package et.elisa.stp.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * B5 fail-fast guard: placeholder admin credentials must abort prod boot,
 * while dev/test profiles still allow the defaults for local lab runs.
 */
class AdminSecurityGuardTest {

    @Test
    void prodProfileWithAllPlaceholdersIsRejected() {
        List<String> problems = AdminSecurityGuard.check("prod",
                "stp-dev-session-hmac-secret-change-me", "stp-admin", "admin", "stp-admin");

        assertThat(problems).containsExactly(
                "stp.admin.session-hmac-secret",
                "stp.admin.api-key",
                "stp.admin.username",
                "stp.admin.first-run-password");
    }

    @Test
    void prodProfileWithRealCredentialsIsAccepted() {
        List<String> problems = AdminSecurityGuard.check("prod",
                "kq9f2m-vZ3pQ-s7xT1w", "ops-2026-a1b2c3", "smp-owner", "P@ssw0rd-x7!ku4");

        assertThat(problems).isEmpty();
    }

    @Test
    void devAndTestProfilesAllowPlaceholders() {
        assertThat(AdminSecurityGuard.check("dev", "stp-dev-session-hmac-secret-change-me",
                "stp-admin", "admin", "stp-admin")).isEmpty();
        assertThat(AdminSecurityGuard.check("test", "stp-dev-session-hmac-secret-change-me",
                "stp-admin", "admin", "stp-admin")).isEmpty();
    }

    @Test
    void partialPlaceholderIsReportedIndividually() {
        List<String> problems = AdminSecurityGuard.check("prod",
                "stp-dev-session-hmac-secret-change-me", "real-key", "admin", "real-pass");

        assertThat(problems).containsExactly("stp.admin.session-hmac-secret", "stp.admin.username");
    }
}