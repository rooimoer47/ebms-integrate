package dev.ebms.adapter.in.msh;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ebms.security")
public class EbmsSecurityProperties {

    private String keystorePath = "";
    private String keystorePassword = "";
    private String keystoreAlias = "ebms";

    public boolean keystoreConfigured() {
        return keystorePath != null && !keystorePath.isBlank();
    }

    public String getKeystorePath() { return keystorePath; }
    public void setKeystorePath(String keystorePath) { this.keystorePath = keystorePath; }

    public String getKeystorePassword() { return keystorePassword; }
    public void setKeystorePassword(String keystorePassword) { this.keystorePassword = keystorePassword; }

    public String getKeystoreAlias() { return keystoreAlias; }
    public void setKeystoreAlias(String keystoreAlias) { this.keystoreAlias = keystoreAlias; }
}
