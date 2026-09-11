package top.rayawa.monitor.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuthServiceTest {

    private final AuthService authService = new AuthService();

    @Test
    void acceptsBothConfiguredAccounts() {
        assertThat(authService.authenticate("admin", "admin123")).isTrue();
        assertThat(authService.authenticate("monitor", "monitor123")).isTrue();
    }

    @Test
    void rejectsUnknownUsersAndWrongPasswords() {
        assertThat(authService.authenticate("admin", "wrong")).isFalse();
        assertThat(authService.authenticate("unknown", "admin123")).isFalse();
        assertThat(authService.authenticate(null, null)).isFalse();
    }

    @Test
    void trimsUsernameButKeepsPasswordExact() {
        assertThat(authService.authenticate(" admin ", "admin123")).isTrue();
        assertThat(authService.authenticate("admin", " admin123 ")).isFalse();
    }
}
