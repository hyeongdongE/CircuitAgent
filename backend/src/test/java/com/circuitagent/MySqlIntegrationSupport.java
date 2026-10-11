package com.circuitagent;

// MySqlIntegrationSupport: Testcontainers MySQL에서 저장·복원 또는 스키마 제약을 검증한다. 의도적인 실패 입력은 무결성과 롤백 확인용이다.

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;

/** Real MySQL 8; failures are not silently skipped when Docker is unavailable. */
@SpringBootTest(classes = CircuitAgentApplication.class)
public abstract class MySqlIntegrationSupport {
  private static final MySQLContainer<?> MYSQL =
      new MySQLContainer<>("mysql:8.0.43")
          .withDatabaseName("circuitagent")
          .withUsername("circuitagent")
          .withPassword("integration-test-only")
          .withUrlParam("connectionTimeZone", "UTC");

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry properties) {
    MYSQL.start();
    properties.add("spring.datasource.url", MYSQL::getJdbcUrl);
    properties.add("spring.datasource.username", MYSQL::getUsername);
    properties.add("spring.datasource.password", MYSQL::getPassword);
  }
}
