package dev.ebms.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ebms.security")
public class EbmsSecurityProperties {

    private String keystorePath = "";
    private String keystorePassword = "";
    private String keystoreAlias = "ebms";
    private String truststorePath = "";
    private String truststorePassword = "";

    private final Api api = new Api();

    public Api getApi() { return api; }

    /**
     * Credentials for HTTP Basic over the service APIs. Separate from the keystores above: those
     * secure the ebMS conversation with partners, these secure the REST surface our own clients
     * call.
     */
    public static class Api {

        private boolean enabled = true;
        private String username = "";
        private String password = "";
        /**
         * Optional second account: the {@code osdbk.ebms.api.service-secret} that cpa-service and
         * jms-consumer send to the paths absorbed from ebms-api-service.
         */
        private String apiServiceUsername = "";
        private String apiServicePassword = "";

        /**
         * Fails startup rather than serving an unauthenticated write API. Without this, forgetting
         * the secret in one environment is silent, and the thing it leaves open is
         * {@code POST /cpas} -- which decides where our outbound traffic goes.
         */
        public void validate() {
            if (enabled && (username.isBlank() || password.isBlank())) {
                throw new IllegalStateException(
                        "Service API authentication is enabled but has no credentials. Set "
                                + "ebms.security.api.username and ebms.security.api.password "
                                + "(EBMS_API_USERNAME / EBMS_API_PASSWORD), or set "
                                + "ebms.security.api.enabled=false to serve them unauthenticated.");
            }
            if (enabled && apiServiceUsername.isBlank() != apiServicePassword.isBlank()) {
                throw new IllegalStateException(
                        "Only half of the ebms-api-service account is set. Set both "
                                + "ebms.security.api.api-service-username and -password "
                                + "(EBMS_API_SERVICE_USERNAME / EBMS_API_SERVICE_PASSWORD), or neither.");
            }
            if (enabled && apiServiceAccountConfigured() && apiServiceUsername.equals(username)
                    && !apiServicePassword.equals(password)) {
                throw new IllegalStateException(
                        "The ebms-api-service account has the same username as the service API "
                                + "account but a different password; one of the two would never work.");
            }
        }

        public boolean apiServiceAccountConfigured() {
            return !apiServiceUsername.isBlank() && !apiServicePassword.isBlank();
        }

        public String getApiServiceUsername() { return apiServiceUsername; }
        public void setApiServiceUsername(String apiServiceUsername) { this.apiServiceUsername = apiServiceUsername; }

        public String getApiServicePassword() { return apiServicePassword; }
        public void setApiServicePassword(String apiServicePassword) { this.apiServicePassword = apiServicePassword; }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }

        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }

    public boolean keystoreConfigured() {
        return keystorePath != null && !keystorePath.isBlank();
    }

    public boolean truststoreConfigured() {
        return truststorePath != null && !truststorePath.isBlank();
    }

    public String getKeystorePath() { return keystorePath; }
    public void setKeystorePath(String keystorePath) { this.keystorePath = keystorePath; }

    public String getKeystorePassword() { return keystorePassword; }
    public void setKeystorePassword(String keystorePassword) { this.keystorePassword = keystorePassword; }

    public String getKeystoreAlias() { return keystoreAlias; }
    public void setKeystoreAlias(String keystoreAlias) { this.keystoreAlias = keystoreAlias; }

    public String getTruststorePath() { return truststorePath; }
    public void setTruststorePath(String truststorePath) { this.truststorePath = truststorePath; }

    public String getTruststorePassword() { return truststorePassword; }
    public void setTruststorePassword(String truststorePassword) { this.truststorePassword = truststorePassword; }
}
