package com.olist.datasets.clickhouse;

import com.olist.datasets.config.ClickHouseProperties;
import org.springframework.stereotype.Component;

@Component
public class ClickHouseClientFactory {

    public ClickHouseClient create(ClickHouseProperties properties) {
        return new JdbcClickHouseClient(properties);
    }
}