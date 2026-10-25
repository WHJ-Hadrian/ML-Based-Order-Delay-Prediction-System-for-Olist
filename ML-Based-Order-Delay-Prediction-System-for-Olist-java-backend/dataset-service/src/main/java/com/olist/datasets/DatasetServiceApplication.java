package com.olist.datasets;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.olist.datasets.mapper")
public class DatasetServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DatasetServiceApplication.class, args);
    }
}
