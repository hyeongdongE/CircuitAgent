package com.circuitagent;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;

/** Real MySQL 8; failures are not silently skipped when Docker is unavailable. */
@SpringBootTest(classes = CircuitAgentApplication.class)
public abstract class MySqlIntegrationSupport {
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.43")
        .withDatabaseName("circuitagent").withUsername("circuitagent").withPassword("integration-test-only")
        .withUrlParam("connectionTimeZone", "UTC");
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        MYSQL.start();
        properties.add("spring.datasource.url", MYSQL::getJdbcUrl);
        properties.add("spring.datasource.username", MYSQL::getUsername);
        properties.add("spring.datasource.password", MYSQL::getPassword);
    }
}
