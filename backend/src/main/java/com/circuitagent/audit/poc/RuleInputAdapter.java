package com.circuitagent.audit.poc;

import java.util.List;

/** 팀원 Stage 01 출력과 결정론적 Checker 사이의 교체 경계. 현재 구현은 정형 PoC 계약만 검증하며 cp-v1 Parser가 구현됐다는 의미는 아니다. */
public interface RuleInputAdapter {
  List<AuditRule> validate(List<AuditRule> rules);
}
