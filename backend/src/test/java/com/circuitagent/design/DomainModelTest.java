package com.circuitagent.design;

// DomainModelTest: 대상 기능의 정상 동작과 오류 경계를 검증한다. 입력과 기대값은 각 테스트 메서드에 명시한다.

import static org.assertj.core.api.Assertions.*;

import com.circuitagent.design.model.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class DomainModelTest {
  @Test
  void missingAttributesStayAbsentAndSpecificationsCanBeShared() {
    var spec = new PartSpecification(UUID.randomUUID(), null, "MPN", "resistor", Map.of());
    var r1 = new ComponentInstance("R1", null, spec.id(), Map.of(), null);
    var r2 = new ComponentInstance("R2", null, spec.id(), Map.of(), null);
    var design =
        new CircuitDesign(
            UUID.randomUUID(),
            "test",
            "v1",
            Map.of("R1", r1, "R2", r2),
            Map.of(spec.id(), spec),
            List.of(new ComponentPin(new PinKey("R1", "1"), null, null)),
            Map.of(),
            List.of(),
            List.of(),
            Map.of());
    assertThat(design.specifications()).hasSize(1);
    assertThat(spec.manufacturer()).isNull();
    assertThat(spec.attributes()).doesNotContainKey("Resistance");
    assertThat(r1.value()).isNull();
    assertThat(design.connections()).isEmpty();
    assertThatThrownBy(() -> design.components().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void rejectsDanglingOrMultipleNetConnections() {
    var spec = new PartSpecification(UUID.randomUUID(), null, null, null, Map.of());
    var c = new ComponentInstance("U1", null, spec.id(), Map.of(), null);
    var pin = new PinKey("U1", "1");
    assertThatThrownBy(
            () ->
                new CircuitDesign(
                    UUID.randomUUID(),
                    "test",
                    "v1",
                    Map.of("U1", c),
                    Map.of(spec.id(), spec),
                    List.of(new ComponentPin(pin, "IN", "power_in")),
                    Map.of(
                        "1",
                        new CircuitNet("1", "/power/VIN", null),
                        "2",
                        new CircuitNet("2", "GND", null)),
                    List.of(new PinConnection(pin, "1"), new PinConnection(pin, "2")),
                    List.of(),
                    Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ambiguous");
  }

  @Test
  void preservesRawSourcesDefensively() {
    var row = new HashMap<String, String>();
    row.put("Value", "1kΩ");
    var c = new ComponentInstance("R1", "1kΩ", UUID.randomUUID(), Map.of("bom", row), null);
    row.put("Value", "0");
    assertThat(c.rawSources().get("bom").get("Value")).isEqualTo("1kΩ");
  }
}
