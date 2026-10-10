package com.example.airline;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(AirlineProperties.class)
public class AirlineApplication {
    public static void main(String[] args) {
        SpringApplication.run(AirlineApplication.class, args);
    }
}
