package dev.ebms.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ebms.security")
public class EbmsSecurityProperties {

    private String keystorePath = "";
    private String keystorePassword = "";
    private String keystoreAlias = "ebms";
    private String truststorePath = "";
    private String truststorePassword = "";

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
