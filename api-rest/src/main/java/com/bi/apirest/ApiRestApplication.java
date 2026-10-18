package com.bi.apirest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ApiRestApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiRestApplication.class, args);
    }
}
