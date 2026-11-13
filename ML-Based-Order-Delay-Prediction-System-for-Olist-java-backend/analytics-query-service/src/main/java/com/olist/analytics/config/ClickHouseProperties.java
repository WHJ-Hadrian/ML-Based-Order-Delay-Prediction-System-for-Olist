package com.olist.analytics.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "olist.clickhouse")
public class ClickHouseProperties {

    private String url = "jdbc:clickhouse://localhost:8123";
    private String database = "olist_datasets";
    private String username = "olist";
    private String password = "";
    private int timeoutSeconds = 30;

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getDatabase() { return database; }
    public void setDatabase(String database) { this.database = database; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
}
