package com.cellforge.cell;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.cellforge.cell", "com.cellforge.shared"})
@EnableScheduling
public class CellApplication {

    public static void main(String[] args) {
        new SpringApplicationBuilder(CellApplication.class).properties("spring.config.name=cell").run(args);
    }
}
