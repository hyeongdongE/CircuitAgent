package com.circuitagent.design.graph;

import com.circuitagent.design.model.*;
import com.circuitagent.design.persistence.CircuitDesignStore;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * DB에 저장된 설계를 복원해 CircuitGraph Snapshot으로 제공한다. 단일 조회 편의 메서드는 매번 DB를 읽으므로 여러 Rule을 실행할 때는 snapshot을
 * 한 번 받아 재사용한다.
 */
@Service
public class DesignQueryService {
  private final CircuitDesignStore store;

  public DesignQueryService(CircuitDesignStore store) {
    this.store = store;
  }

  public CircuitGraph snapshot(UUID designId) {
    return new CircuitGraph(store.load(designId));
  }

  public ComponentInstance getComponent(UUID id, String ref) {
    return snapshot(id).getComponent(ref);
  }

  public List<ComponentPin> getComponentPins(UUID id, String ref) {
    return snapshot(id).getComponentPins(ref);
  }

  public Optional<CircuitNet> getNetOfPin(UUID id, String ref, String number) {
    return snapshot(id).getNetOfPin(ref, number);
  }

  public List<ComponentPin> getConnectedPins(UUID id, String netId) {
    return snapshot(id).getConnectedPins(netId);
  }

  public Set<ComponentInstance> getComponentsOnNet(UUID id, String netId) {
    return snapshot(id).getComponentsOnNet(netId);
  }

  public Map<String, AttributeValue> getComponentAttributes(UUID id, String ref) {
    return snapshot(id).getComponentAttributes(ref);
  }
}
