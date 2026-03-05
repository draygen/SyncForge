package com.mft.server.config;

import com.mft.server.model.SystemMetadata;
import com.mft.server.repository.SystemMetadataRepository;
import org.flywaydb.core.Flyway;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DataInitializer {

    public DataInitializer(Flyway flyway) {
        // This constructor injection forces Flyway to run BEFORE this bean is created.
    }

    @Bean
    public CommandLineRunner initData(SystemMetadataRepository systemMetadataRepository) {
        return args -> {
            systemMetadataRepository.findTopByOrderByIdDesc().ifPresent(m -> {
                System.out.println("MFT Server Running Version: " + m.getVersion());
                System.out.println("Data Model Version: " + m.getDataModelVersion());
            });
        };
    }
}
