package com.bi.apigrpc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ApiGrpcApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGrpcApplication.class, args);
    }
}
