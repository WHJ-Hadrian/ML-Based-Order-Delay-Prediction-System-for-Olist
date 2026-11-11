package com.olist.models;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ModelManagementServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ModelManagementServiceApplication.class, args);
    }
}