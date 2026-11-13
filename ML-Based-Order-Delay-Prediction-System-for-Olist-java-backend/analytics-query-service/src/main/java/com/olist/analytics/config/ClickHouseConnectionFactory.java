package com.olist.analytics.config;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.springframework.stereotype.Component;

/**
 * 只读 ClickHouse 连接工厂。
 * 使用 DriverManager + jdbc:clickhouse 形式（与 dataset-service 一致），
 * 避免 DataSource Bean 抢占 Spring 的数据源自动配置。
 */
@Component
public class ClickHouseConnectionFactory {

    private final String url;
    private final String username;
    private final String password;

    public ClickHouseConnectionFactory(ClickHouseProperties properties) {
        this.url = withDisabledCompression(properties.getUrl());
        this.username = properties.getUsername();
        this.password = properties.getPassword();
    }

    public Connection open() throws SQLException {
        return DriverManager.getConnection(url, username, password);
    }

    private static String withDisabledCompression(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return baseUrl;
        }
        StringBuilder adjusted = new StringBuilder(baseUrl);
        char separator = baseUrl.indexOf('?') >= 0 ? '&' : '?';
        if (!baseUrl.contains("compress=")) {
            adjusted.append(separator).append("compress=0");
            separator = '&';
        }
        if (!baseUrl.contains("decompress=")) {
            adjusted.append(separator).append("decompress=0");
        }
        return adjusted.toString();
    }
}
