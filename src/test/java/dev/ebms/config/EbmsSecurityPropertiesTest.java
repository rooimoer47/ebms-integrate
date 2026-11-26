package dev.ebms.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EbmsSecurityPropertiesTest {

    private EbmsSecurityProperties.Api api(boolean enabled, String user, String password) {
        EbmsSecurityProperties.Api api = new EbmsSecurityProperties.Api();
        api.setEnabled(enabled);
        api.setUsername(user);
        api.setPassword(password);
        return api;
    }

    @Test
    void enabledWithoutCredentials_failsStartup() {
        assertThatThrownBy(() -> api(true, "", "").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ebms.security.api.username");

        assertThatThrownBy(() -> api(true, "ebms", "").validate())
                .isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> api(true, "", "secret").validate())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void enabledWithCredentials_isAccepted() {
        assertThatCode(() -> api(true, "ebms", "secret").validate()).doesNotThrowAnyException();
    }

    /** Opting out has to be explicit, but it must remain possible -- docker-compose has no secret. */
    @Test
    void disabled_doesNotRequireCredentials() {
        assertThatCode(() -> api(false, "", "").validate()).doesNotThrowAnyException();
    }

    // ---------- P4-A3: ebms-api-service's account ----------

    private EbmsSecurityProperties.Api withApiService(String user, String password) {
        EbmsSecurityProperties.Api api = api(true, "ebms", "secret");
        api.setApiServiceUsername(user);
        api.setApiServicePassword(password);
        return api;
    }

    @Test
    void apiServiceAccount_isOptional() {
        assertThatCode(() -> withApiService("", "").validate()).doesNotThrowAnyException();
        assertThat(withApiService("", "").apiServiceAccountConfigured()).isFalse();
        assertThat(withApiService("ebms-api", "s3").apiServiceAccountConfigured()).isTrue();
    }

    /** Half a secret in one environment would otherwise mean a silent 401 for the console. */
    @Test
    void halfAnApiServiceAccount_failsStartup() {
        assertThatThrownBy(() -> withApiService("ebms-api", "").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EBMS_API_SERVICE_PASSWORD");
        assertThatThrownBy(() -> withApiService("", "s3").validate())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void sameUsernameWithADifferentPassword_failsStartup() {
        assertThatThrownBy(() -> withApiService("ebms", "other").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("same username");
        assertThatCode(() -> withApiService("ebms", "secret").validate()).doesNotThrowAnyException();
    }

    @Test
    void defaultsToEnabled() {
        assertThat(new EbmsSecurityProperties().getApi().isEnabled()).isTrue();
    }
}
