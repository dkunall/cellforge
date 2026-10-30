package com.cellforge.edge.config;

import com.cellforge.edge.directory.application.Directory;
import java.util.Map;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Cells are declared in configuration and upserted at startup; adding a cell is a config change and a restart. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CellRegistry.CellsProperties.class)
class CellRegistry {

    @ConfigurationProperties("cellforge")
    record CellsProperties(Map<String, String> cells) {
    }

    @Bean
    ApplicationRunner registerCells(CellsProperties properties, Directory directory) {
        return (ApplicationArguments args) -> properties.cells().forEach(directory::registerCell);
    }
}
