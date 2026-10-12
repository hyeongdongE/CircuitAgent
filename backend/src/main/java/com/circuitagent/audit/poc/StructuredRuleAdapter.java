package com.circuitagent.audit.poc;

import java.math.BigDecimal;
import java.net.URI;
import java.util.*;
import org.springframework.stereotype.Component;

/**
 * 정형 Rule의 필수 입력·고유 ID·단위·숫자/길이 한도를 검사한다. 회로 사실은 조회하지 않으며 HTTPS URL/SHA의 문법 검사도 데이터시트 내용 검증과는 다르다.
 */
@Component
public class StructuredRuleAdapter implements RuleInputAdapter {
  private static final Set<String> UNITS = Set.of("Ω", "F", "H", "V", "A", "%");

  @Override
  public List<AuditRule> validate(List<AuditRule> rules) {
    if (rules == null || rules.isEmpty() || rules.size() > 100) throw invalid();
    // 중복 ID는 같은 실행의 결과와 DB unique 계약을 모호하게 하므로 입력 단계에서 거절한다.
    var ids = new HashSet<String>();
    for (var r : rules) {
      if (r == null || r.type() == null || r.target() == null || r.source() == null)
        throw invalid();
      text(r.id(), 128);
      text(r.version(), 64);
      text(r.condition(), 2000);
      text(r.target().reference(), 191);
      if (!ids.add(r.id())) throw invalid();
      var s = r.source();
      text(s.url(), 1000);
      text(s.revision(), 128);
      text(s.section(), 256);
      text(s.summary(), 2000);
      // Source 주소는 문자열로 보관할 뿐 서버가 호출하지 않는다. HTTPS/SHA 문법을 만족해도 내용 검증은 별도다.
      var uri = URI.create(s.url());
      if (!"https".equals(uri.getScheme())
          || uri.getHost() == null
          || uri.getUserInfo() != null
          || s.page() < 1) throw invalid();
      if (s.sha256() == null || !s.sha256().matches("[0-9a-f]{64}")) throw invalid();
      if (!r.applicable()) text(r.notApplicableReason(), 2000);
      if (r.target().partName() != null) text(r.target().partName(), 191);
      if (r.type() == AuditRule.Type.PIN_CONNECTED
          || r.type() == AuditRule.Type.NET_MATCH
          || r.type() == AuditRule.Type.FB_DIVIDER) text(r.target().pinNumber(), 128);
      if (r.type() == AuditRule.Type.NET_MATCH) text(r.expectedNet(), 191);
      if (r.type() == AuditRule.Type.VALUE_RANGE || r.type() == AuditRule.Type.ATTRIBUTE_PRESENT) {
        text(r.attribute(), 128);
        if (r.unit() == null || !UNITS.contains(r.unit())) throw invalid();
      }
      if (r.type() == AuditRule.Type.VALUE_RANGE) bounds(r.expected());
      if (r.type() == AuditRule.Type.FB_DIVIDER) {
        if (!"V".equals(r.unit()) || r.divider() == null) throw invalid();
        var d = r.divider();
        text(d.upperReference(), 191);
        text(d.lowerReference(), 191);
        text(d.groundPin(), 128);
        if (d.upperReference().equals(d.lowerReference())) throw invalid();
        positive(d.vref());
        positive(d.vrefMin());
        positive(d.vrefMax());
        if (d.vrefMin().compareTo(d.vref()) > 0 || d.vrefMax().compareTo(d.vref()) < 0)
          throw invalid();
        tolerance(d.resistorTolerancePercent(), false);
        if (r.expected() != null) bounds(r.expected());
      }
    }
    return List.copyOf(rules);
  }

  private static void bounds(AuditRule.Bounds b) {
    if (b == null) throw invalid();
    number(b.min());
    number(b.max());
    if (b.min().compareTo(b.max()) > 0) throw invalid();
    tolerance(b.tolerancePercent(), true);
  }

  private static void tolerance(BigDecimal v, boolean required) {
    if (v == null) {
      if (required) throw invalid();
      return;
    }
    number(v);
    if (v.signum() < 0 || v.compareTo(BigDecimal.valueOf(50)) > 0) throw invalid();
  }

  private static void positive(BigDecimal v) {
    number(v);
    if (v.signum() <= 0) throw invalid();
  }

  // BigDecimal의 정밀도/scale 한도로 과도한 지수와 메모리 사용을 방지한다.
  private static void number(BigDecimal v) {
    if (v == null || v.precision() > 32 || Math.abs((long) v.scale()) > 32) throw invalid();
  }

  private static void text(String s, int limit) {
    if (s == null
        || s.isBlank()
        || s.length() > limit
        || s.chars().anyMatch(Character::isISOControl)) throw invalid();
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException(
        "Invalid poc-rule-v1 contract; explicit target, condition, unit, bounds/tolerance and source required");
  }
}
