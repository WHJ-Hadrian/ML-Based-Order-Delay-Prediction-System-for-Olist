package com.olist.gateway.registration;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "olist.registration")
public class RegistrationProperties {

    private String keycloakBaseUrl;
    private String keycloakRealm;
    private String keycloakClientId;
    private String keycloakClientSecret;
    private String otpHmacSecret;
    private String mailFrom;
    private Duration codeTtl = Duration.ofMinutes(10);
    private Duration resendDelay = Duration.ofSeconds(60);
    private Duration verifiedTokenTtl = Duration.ofMinutes(10);
    private Duration ipWindow = Duration.ofMinutes(15);
    private int maxCodesPerIpWindow = 10;
    private int maxCodeAttempts = 5;

    public String getKeycloakBaseUrl() { return keycloakBaseUrl; }
    public void setKeycloakBaseUrl(String keycloakBaseUrl) { this.keycloakBaseUrl = keycloakBaseUrl; }
    public String getKeycloakRealm() { return keycloakRealm; }
    public void setKeycloakRealm(String keycloakRealm) { this.keycloakRealm = keycloakRealm; }
    public String getKeycloakClientId() { return keycloakClientId; }
    public void setKeycloakClientId(String keycloakClientId) { this.keycloakClientId = keycloakClientId; }
    public String getKeycloakClientSecret() { return keycloakClientSecret; }
    public void setKeycloakClientSecret(String keycloakClientSecret) { this.keycloakClientSecret = keycloakClientSecret; }
    public String getOtpHmacSecret() { return otpHmacSecret; }
    public void setOtpHmacSecret(String otpHmacSecret) { this.otpHmacSecret = otpHmacSecret; }
    public String getMailFrom() { return mailFrom; }
    public void setMailFrom(String mailFrom) { this.mailFrom = mailFrom; }
    public Duration getCodeTtl() { return codeTtl; }
    public void setCodeTtl(Duration codeTtl) { this.codeTtl = codeTtl; }
    public Duration getResendDelay() { return resendDelay; }
    public void setResendDelay(Duration resendDelay) { this.resendDelay = resendDelay; }
    public Duration getVerifiedTokenTtl() { return verifiedTokenTtl; }
    public void setVerifiedTokenTtl(Duration verifiedTokenTtl) { this.verifiedTokenTtl = verifiedTokenTtl; }
    public Duration getIpWindow() { return ipWindow; }
    public void setIpWindow(Duration ipWindow) { this.ipWindow = ipWindow; }
    public int getMaxCodesPerIpWindow() { return maxCodesPerIpWindow; }
    public void setMaxCodesPerIpWindow(int maxCodesPerIpWindow) { this.maxCodesPerIpWindow = maxCodesPerIpWindow; }
    public int getMaxCodeAttempts() { return maxCodeAttempts; }
    public void setMaxCodeAttempts(int maxCodeAttempts) { this.maxCodeAttempts = maxCodeAttempts; }
}
