package com.olist.datasets.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ClickHouseProperties.class)
public class ClickHouseConfiguration {
}