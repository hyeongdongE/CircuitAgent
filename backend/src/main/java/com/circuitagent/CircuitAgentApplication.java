package com.circuitagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot 진입점. 하위 패키지의 Controller/Service/Repository/Parser를 자동 등록한다. 실제 처리 흐름은
 * DesignImportService(설계 생성)와 AuditService(검증 실행)에서 시작한다.
 */
@SpringBootApplication
public class CircuitAgentApplication {
  public static void main(String[] args) {
    SpringApplication.run(CircuitAgentApplication.class, args);
  }
}
