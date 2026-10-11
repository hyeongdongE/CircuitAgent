package com.circuitagent.audit.poc;

import com.circuitagent.design.graph.CircuitGraph;
import com.circuitagent.design.model.*;
import java.math.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;

/**
 * 회로 Snapshot과 정형 Rule만으로 사실과 제한된 계산을 평가한다. BigDecimal을 사용하고 누락·충돌·미실장·신원 미확인은 UNKNOWN으로 유지한다. LLM
 * 판정, Net 이름에서 전압 추정, 회로 동작 시뮬레이션은 수행하지 않는다.
 */
@Component
public class DeterministicRuleChecker {
  private static final MathContext MC = MathContext.DECIMAL128;

  public AuditResult evaluate(CircuitGraph graph, AuditRule rule) {
    // Rule마다 독립 Work를 만들어 누락 사유와 사용 근거가 다른 Rule에 섞이지 않게 한다.
    var work = new Work(graph, rule);
    try {
      // 적용 제외는 데이터 부족과 다르다. 대상 미실장도 그 대상의 실장 Rule에는 적용되지 않는다.
      if (!rule.applicable())
        return work.result(AuditStatus.NOT_APPLICABLE, null, rule.notApplicableReason());
      var target = work.component(rule.target().reference());
      if (target == null)
        return work.result(AuditStatus.UNKNOWN, null, "Target component is missing");
      if (rule.target().partName() != null
          && !rule.target()
              .partName()
              .equals(
                  graph
                      .getComponentAttributes(target.reference())
                      .getOrDefault("Part Name", AttributeValue.text(null))
                      .raw()))
        return work.result(
            AuditStatus.NOT_APPLICABLE,
            null,
            "Part Name applicability does not match; no MPN guessing");
      if (target.assemblyStatus() == AssemblyStatus.DNP)
        return work.result(AuditStatus.NOT_APPLICABLE, null, "Target is explicitly not mounted");
      if (target.assemblyStatus() == AssemblyStatus.UNSPECIFIED)
        work.missing.add("Target assembly state is unspecified");
      // MPN 문자열이 있어도 독립 검증/문서 적용성 attestation이 없으면 UNKNOWN이다.
      if (rule.target().requireVerifiedMpn()) {
        if (graph.getComponentSpecification(target.reference()).mpn() == null)
          work.missing.add("Verified manufacturer MPN / applicable silicon revision");
        else
          work.missing.add(
              "Independent MPN / datasheet applicability attestation is unavailable in poc-rule-v1");
      }
      // 각 Type은 지원된 한 연산으로만 평가한다. nominal 값 확인을 실제 동작 적합성으로 확대하지 않는다.
      return switch (rule.type()) {
        case VALUE_RANGE -> {
          var value = work.attribute(target.reference(), rule.attribute(), rule.unit());
          yield work.compare(
              value,
              "Nominal BOM attribute compared with explicit bounds; no operating behavior implied");
        }
        case ATTRIBUTE_PRESENT -> {
          var value = work.attribute(target.reference(), rule.attribute(), rule.unit());
          yield work.result(
              value == null ? AuditStatus.UNKNOWN : AuditStatus.PASS,
              value,
              "Explicit numeric attribute/rating evidence lookup");
        }
        case PIN_CONNECTED, NET_MATCH -> {
          var p = work.pin(target.reference(), rule.target().pinNumber());
          if (p.status() != CircuitGraph.ConnectionStatus.CONNECTED) {
            work.missing.add("Pin " + p.key() + " connection state: " + p.status());
            yield work.result(
                AuditStatus.UNKNOWN,
                null,
                "No unambiguous populated connection can be established; TERM is not automatically an error");
          }
          // 같은 Net의 부품은 논리 이웃이다. PIN_CONNECTED는 알려진 실장 이웃이 있어야 populated 연결을 확인할 수 있다.
          var neighbors =
              graph.getComponentsOnNet(p.net().id()).stream()
                  .filter(c -> !c.reference().equals(target.reference()))
                  .toList();
          work.calculation.put(
              "sameNetNeighborReferences",
              neighbors.stream()
                  .map(ComponentInstance::reference)
                  .sorted()
                  .collect(java.util.stream.Collectors.joining(",")));
          if (rule.type() == AuditRule.Type.PIN_CONNECTED)
            for (var c : neighbors) work.component(c.reference());
          if (rule.type() == AuditRule.Type.PIN_CONNECTED
              && neighbors.stream().noneMatch(c -> c.assemblyStatus() == AssemblyStatus.FITTED))
            work.missing.add(
                "No known fitted neighbor on target net (DNP is logical topology only)");
          var status =
              rule.type() == AuditRule.Type.NET_MATCH && !rule.expectedNet().equals(p.net().name())
                  ? AuditStatus.FAIL
                  : AuditStatus.PASS;
          yield work.result(
              status,
              null,
              "Explicit net/topology check; no actual pin voltage or conducting state inferred");
        }
        case FB_DIVIDER -> work.divider();
      };
      // 엔진 예외는 FAIL이 아니다. 내부 예외 문자열을 Evidence나 응답으로 유출하지 않는다.
    } catch (RuntimeException e) {
      return work.result(
          AuditStatus.ERROR,
          null,
          "Rule execution failed internally; no conformity judgement produced");
    }
  }

  // 판정에 실제 사용한 부품/핀/속성을 모으는 Rule별 작업 공간. 누락/충돌 사유도 함께 누적한다.
  private static final class Work {
    final CircuitGraph graph;
    final AuditRule rule;
    final Set<String> missing = new LinkedHashSet<>();
    final Map<String, ComponentInstance> parts = new LinkedHashMap<>();
    final Map<String, PartSpecification> specs = new LinkedHashMap<>();
    final List<CircuitGraph.PinContext> pins = new ArrayList<>();
    final Map<String, String> calculation = new LinkedHashMap<>();
    final Set<ImportIssue> issues = new LinkedHashSet<>();

    Work(CircuitGraph g, AuditRule r) {
      graph = g;
      rule = r;
    }

    // 부품을 가져올 때 해당 이슈도 확인한다. 충돌 데이터의 숫자가 있어도 확정 판정을 하지 않는다.
    ComponentInstance component(String ref) {
      try {
        var c = graph.getComponent(ref);
        parts.put(ref, c);
        specs.put(ref, graph.getComponentSpecification(ref));
        var found = graph.getComponentIssues(ref);
        issues.addAll(found);
        if (found.stream()
            .anyMatch(
                i ->
                    i.code() == ImportIssue.Code.DUPLICATE_REFERENCE
                        || i.code() == ImportIssue.Code.SPECIFICATION_CONFLICT
                        || i.code() == ImportIssue.Code.VALUE_MISMATCH
                        || i.severity() == ImportIssue.Severity.ERROR))
          missing.add("Conflicting/invalid import data for " + ref);
        return c;
      } catch (NoSuchElementException e) {
        missing.add("Component " + ref);
        return null;
      }
    }

    CircuitGraph.PinContext pin(String ref, String number) {
      var p = graph.getPinContext(ref, number);
      pins.add(p);
      return p;
    }

    // normalized 값과 요청 SI 단위가 모두 맞아야 숫자로 사용한다. raw 문자열만 있으면 UNKNOWN이다.
    BigDecimal attribute(String ref, String name, String unit) {
      var c = component(ref);
      if (c == null) return null;
      var a = graph.getComponentAttributes(ref).get(name);
      if (a == null || a.normalized() == null) {
        missing.add(ref + "." + name);
        return null;
      }
      if (!unit.equals(a.unit())) {
        missing.add(ref + "." + name + " incompatible unit (expected " + unit + ")");
        return null;
      }
      calculation.put(
          ref + "." + name, a.normalized().toPlainString() + " " + a.unit() + "; raw=" + a.raw());
      return a.normalized();
    }

    // 허용 구간은 포함 비교이며 min/max 절대값에 명시 비교 오차를 적용한다. missing 사유는 최종 상태에서 우선한다.
    AuditResult compare(BigDecimal value, String reason) {
      if (value == null)
        return result(AuditStatus.UNKNOWN, null, "Numeric input is missing or incompatible");
      var b = rule.expected();
      if (b == null) {
        missing.add("Explicit expected target/range");
        return result(
            AuditStatus.UNKNOWN, value, "Calculated nominal value is not a conformity judgement");
      }
      var t = b.tolerancePercent().divide(BigDecimal.valueOf(100), MC);
      var low = b.min().subtract(b.min().abs().multiply(t, MC), MC);
      var high = b.max().add(b.max().abs().multiply(t, MC), MC);
      calculation.put(
          "allowedInterval", low.toPlainString() + ".." + high.toPlainString() + " " + rule.unit());
      calculation.put("comparisonTolerancePercent", b.tolerancePercent().toPlainString());
      return result(
          value.compareTo(low) >= 0 && value.compareTo(high) <= 0
              ? AuditStatus.PASS
              : AuditStatus.FAIL,
          value,
          reason);
    }

    // FB/GND 및 두 저항의 반대 핀을 실제 연결로 확인한다. Net 이름으로 출력 조건을 만들지 않는다.
    AuditResult divider() {
      var d = rule.divider();
      var fb = pin(rule.target().reference(), rule.target().pinNumber());
      var ground = pin(rule.target().reference(), d.groundPin());
      if (fb.status() != CircuitGraph.ConnectionStatus.CONNECTED
          || ground.status() != CircuitGraph.ConnectionStatus.CONNECTED) {
        missing.add("Unambiguous FB and GND pin mapping/connections");
        return result(AuditStatus.UNKNOWN, null, "Divider topology unavailable");
      }
      var up = component(d.upperReference());
      var lo = component(d.lowerReference());
      if (up == null || lo == null)
        return result(AuditStatus.UNKNOWN, null, "Divider component missing");
      // NoMount/미확정 저항이면 계산하지 않는다. Netlist topology 존재와 실제 실장은 별개다.
      for (var c : List.of(up, lo))
        if (c.assemblyStatus() != AssemblyStatus.FITTED)
          missing.add(c.reference() + " is not confirmed fitted: " + c.assemblyStatus());
      if (up.assemblyStatus() != AssemblyStatus.FITTED
          || lo.assemblyStatus() != AssemblyStatus.FITTED)
        return result(
            AuditStatus.UNKNOWN,
            null,
            "Unpopulated or unspecified divider cannot establish an operating set point");
      var uOther = opposite(up.reference(), fb.net().id());
      var lOther = opposite(lo.reference(), fb.net().id());
      if (uOther == null
          || lOther == null
          || !lOther.net().id().equals(ground.net().id())
          || uOther.net().id().equals(ground.net().id())) {
        missing.add("Verified output→upper→FB→lower→GND topology");
        return result(AuditStatus.UNKNOWN, null, "Divider topology is incomplete or contradictory");
      }
      var ru = attribute(up.reference(), "Resistance", "Ω");
      var rl = attribute(lo.reference(), "Resistance", "Ω");
      if (ru == null || rl == null || ru.signum() < 0 || rl.signum() <= 0) {
        missing.add("Nonnegative upper and positive lower resistance (no division by zero)");
        return result(AuditStatus.UNKNOWN, null, "Divider numeric inputs invalid");
      }
      calculation.put("formula", "Vout = Vref * (1 + Rupper / Rlower)");
      calculation.put("Vref", d.vref().toPlainString() + " V");
      calculation.put("outputNetLabelNotMeasuredVoltage", uOther.net().name());
      // 기준 전압과 저항에서 조건부 nominal만 계산한다. 목표/공차/신원이 부족하면 결과 상태는 UNKNOWN이다.
      var nominal = d.vref().multiply(BigDecimal.ONE.add(ru.divide(rl, MC), MC), MC);
      if (d.resistorTolerancePercent() == null)
        missing.add("Verified resistor tolerance percentage");
      else {
        var t = d.resistorTolerancePercent().divide(BigDecimal.valueOf(100), MC);
        var one = BigDecimal.ONE;
        // worst-case 하한은 낮은 Vref/upper와 높은 lower, 상한은 그 반대로 계산한다.
        var low =
            d.vrefMin()
                .multiply(
                    one.add(
                        ru.multiply(one.subtract(t), MC).divide(rl.multiply(one.add(t), MC), MC),
                        MC),
                    MC);
        var high =
            d.vrefMax()
                .multiply(
                    one.add(
                        ru.multiply(one.add(t), MC).divide(rl.multiply(one.subtract(t), MC), MC),
                        MC),
                    MC);
        calculation.put(
            "calculatedWorstCaseIntervalV", low.toPlainString() + ".." + high.toPlainString());
        calculation.put("resistorTolerancePercent", d.resistorTolerancePercent().toPlainString());
        if (rule.expected() != null) {
          var b = rule.expected();
          var margin = b.tolerancePercent().divide(BigDecimal.valueOf(100), MC);
          var allowedLow = b.min().subtract(b.min().abs().multiply(margin, MC));
          var allowedHigh = b.max().add(b.max().abs().multiply(margin, MC));
          return result(
              low.compareTo(allowedLow) >= 0 && high.compareTo(allowedHigh) <= 0
                  ? AuditStatus.PASS
                  : AuditStatus.FAIL,
              nominal,
              "Nominal and resistor/Vref interval evaluated under explicit supplied conditions");
        }
      }
      return compare(nominal, "Nominal equation evaluation under stated conditions");
    }

    // 정확히2핀 부품이며 그중 한 핀만 FB Net에 있어야 반대 핀을 선택할 수 있다.
    CircuitGraph.PinContext opposite(String ref, String fbNet) {
      var ps = graph.getComponentPins(ref);
      if (ps.size() != 2) return null;
      var a = pin(ref, ps.get(0).key().number());
      var b = pin(ref, ps.get(1).key().number());
      if (a.status() != CircuitGraph.ConnectionStatus.CONNECTED
          || b.status() != CircuitGraph.ConnectionStatus.CONNECTED) return null;
      boolean af = a.net().id().equals(fbNet), bf = b.net().id().equals(fbNet);
      return af == bf ? null : af ? b : a;
    }

    // 누락/모호 사유가 하나라도 있으면 수치 비교 결과보다 UNKNOWN을 우선한다. 적용 제외/실행 오류는 별도 상태다.
    AuditResult result(AuditStatus status, BigDecimal measured, String reason) {
      if (status != AuditStatus.ERROR && status != AuditStatus.NOT_APPLICABLE && !missing.isEmpty())
        status = AuditStatus.UNKNOWN;
      var evidence =
          new AuditEvidence(
              rule.source(),
              rule.condition(),
              parts,
              specs,
              pins,
              calculation,
              List.copyOf(issues));
      return new AuditResult(
          UUID.randomUUID(),
          rule.id(),
          rule.version(),
          rule.target().reference(),
          status,
          measured,
          rule.expected(),
          rule.unit(),
          reason,
          List.copyOf(missing),
          evidence,
          Instant.now());
    }
  }
}
