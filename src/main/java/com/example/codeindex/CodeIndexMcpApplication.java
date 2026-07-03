package com.example.codeindex;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class CodeIndexMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(CodeIndexMcpApplication.class, args);
    }
}
