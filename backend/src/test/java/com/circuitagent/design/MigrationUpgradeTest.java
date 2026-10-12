package com.circuitagent.design;

// MigrationUpgradeTest: Testcontainers MySQL에서 저장·복원 또는 스키마 제약을 검증한다. 의도적인 실패 입력은 무결성과 롤백 확인용이다.

import static org.assertj.core.api.Assertions.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;

class MigrationUpgradeTest {
  @Test
  void populatedV1MigratesWithoutLosingExistingRelations() {
    try (var mysql = new MySQLContainer<>("mysql:8.0.43")) {
      mysql.start();
      var ds =
          new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
      Flyway.configure().dataSource(ds).target("1").load().migrate();
      var jdbc = new JdbcTemplate(ds);
      jdbc.update(
          "insert into circuit_design values(UUID_TO_BIN('00000000-0000-0000-0000-000000000001'),'upgrade','v1',now(),'{}')");
      jdbc.update(
          "insert into part_specification values(UUID_TO_BIN('00000000-0000-0000-0000-000000000002'),null,null,null,'{}')");
      jdbc.update(
          "insert into component_instance values(UUID_TO_BIN('00000000-0000-0000-0000-000000000003'),UUID_TO_BIN('00000000-0000-0000-0000-000000000001'),UUID_TO_BIN('00000000-0000-0000-0000-000000000002'),'R1','1k',null,'{}')");
      jdbc.update(
          "insert into component_pin values(UUID_TO_BIN('00000000-0000-0000-0000-000000000004'),UUID_TO_BIN('00000000-0000-0000-0000-000000000003'),'1',null,null)");
      jdbc.update(
          "insert into circuit_net values(UUID_TO_BIN('00000000-0000-0000-0000-000000000005'),UUID_TO_BIN('00000000-0000-0000-0000-000000000001'),'1','GND',null)");
      jdbc.update(
          "insert into pin_connection values(UUID_TO_BIN('00000000-0000-0000-0000-000000000006'),UUID_TO_BIN('00000000-0000-0000-0000-000000000001'),UUID_TO_BIN('00000000-0000-0000-0000-000000000004'),UUID_TO_BIN('00000000-0000-0000-0000-000000000005'))");
      Flyway.configure().dataSource(ds).load().migrate();
      assertThat(
              jdbc.queryForObject("select assembly_status from component_instance", String.class))
          .isEqualTo("UNSPECIFIED");
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from circuit_design_specification", Integer.class))
          .isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from component_pin where design_id is not null", Integer.class))
          .isEqualTo(1);
      assertThat(jdbc.queryForObject("select count(*) from pin_connection", Integer.class))
          .isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from information_schema.referential_constraints where constraint_schema=database() and constraint_name in ('fk_connection_pin_design','fk_connection_net_design')",
                  Integer.class))
          .isEqualTo(2);
    }
  }
}
