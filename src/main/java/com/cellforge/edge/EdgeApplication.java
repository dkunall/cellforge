package com.cellforge.edge;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.cellforge.edge", "com.cellforge.shared"})
@EnableScheduling
public class EdgeApplication {

    public static void main(String[] args) {
        new SpringApplicationBuilder(EdgeApplication.class).properties("spring.config.name=edge").run(args);
    }
}
