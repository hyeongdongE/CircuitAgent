package com.circuitagent.design.persistence;

import com.circuitagent.design.model.CircuitDesign;
import com.circuitagent.design.model.CircuitNet;
import com.circuitagent.design.model.ComponentInstance;
import com.circuitagent.design.model.ComponentPin;
import com.circuitagent.design.model.ImportIssue;
import com.circuitagent.design.model.PartSpecification;
import com.circuitagent.design.model.PinConnection;
import com.circuitagent.design.model.PinKey;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 불변 도메인 모델과 JPA Entity를 연결하는 저장/복원 경계. 전체 설계·사양·핀·Net·이슈·원본 파일을 한 트랜잭션으로 저장하고 도메인 객체로 복원한다.
 * Entity를 API로 직접 반환하지 않아 LAZY 관계와 DB 구조가 외부 모델에 섞이지 않는다.
 */
@Repository
public class CircuitDesignStore {
  private final CircuitDesignRepository repository;
  private final EntityManager em;

  public CircuitDesignStore(CircuitDesignRepository repository, EntityManager em) {
    this.repository = repository;
    this.em = em;
  }

  // FK의 부모부터 저장한다. flush에서 실패해도 원본 파일을 포함한 전체 저장이 한꺼번에 롤백된다.
  @Transactional
  public UUID save(CircuitDesign model, List<SourceFile> files) {
    if (repository.existsById(model.id()))
      throw new IllegalArgumentException("Design ID already exists");
    var design = new CircuitDesignEntity();
    design.id = model.id();
    design.name = model.name();
    design.version = model.version();
    design.importedAt = Instant.now();
    design.sourceMetadata = model.sourceMetadata();
    em.persist(design);
    // 사양 ID가 같으면 동일 사양인지 확인한다. 다른 속성으로 기존 사양을 덮어쓰지 않는다.
    Map<UUID, PartSpecificationEntity> specs = new HashMap<>();
    for (var value : model.specifications().values()) {
      var spec = em.find(PartSpecificationEntity.class, value.id());
      if (spec == null) {
        spec = new PartSpecificationEntity();
        spec.id = value.id();
        spec.manufacturer = value.manufacturer();
        spec.mpn = value.mpn();
        spec.type = value.type();
        spec.attributes = value.attributes();
        em.persist(spec);
      } else if (!toModel(spec).equals(value))
        throw new IllegalArgumentException("Existing specification ID has different attributes");
      specs.put(value.id(), spec);
      design.specifications.add(spec);
    }
    // 도메인 Reference/PinKey/Net ID와 새 DB PK UUID의 대응표를 만들어 FK를 연결한다.
    Map<String, ComponentInstanceEntity> components = new HashMap<>();
    for (var value : model.components().values()) {
      var c = new ComponentInstanceEntity();
      c.id = UUID.randomUUID();
      c.design = design;
      c.reference = value.reference();
      c.value = value.value();
      c.specification = specs.get(value.partSpecificationId());
      c.assemblyStatus = value.assemblyStatus();
      c.rawSources = value.rawSources();
      c.sourcePath = value.sourcePath();
      em.persist(c);
      components.put(c.reference, c);
    }
    Map<PinKey, ComponentPinEntity> pins = new HashMap<>();
    for (var value : model.pins()) {
      var pin = new ComponentPinEntity();
      pin.id = UUID.randomUUID();
      pin.component = components.get(value.key().reference());
      pin.designId = design.id;
      pin.number = value.key().number();
      pin.name = value.name();
      pin.electricalType = value.electricalType();
      pin.disposition = value.disposition();
      pin.sourceMetadata = value.sourceMetadata();
      em.persist(pin);
      pins.put(value.key(), pin);
    }
    Map<String, CircuitNetEntity> nets = new HashMap<>();
    for (var value : model.nets().values()) {
      var net = new CircuitNetEntity();
      net.id = UUID.randomUUID();
      net.design = design;
      net.netCode = value.id();
      net.name = value.name();
      net.sourcePath = value.sourcePath();
      em.persist(net);
      nets.put(value.id(), net);
    }
    for (var value : model.connections()) {
      var connection = new PinConnectionEntity();
      connection.id = UUID.randomUUID();
      connection.design = design;
      connection.pin = pins.get(value.pin());
      connection.net = nets.get(value.netId());
      em.persist(connection);
    }
    // 이슈 순서도 저장하여 전체 모델 복원과 Snapshot hash가 조회 순서에 좌우되지 않게 한다.
    for (int i = 0; i < model.issues().size(); i++) {
      var value = model.issues().get(i);
      var issue = new ImportIssueEntity();
      issue.id = UUID.randomUUID();
      issue.design = design;
      issue.issueOrder = i;
      issue.severity = value.severity();
      issue.code = value.code();
      issue.reference = value.reference();
      issue.source = value.source();
      issue.message = value.message();
      em.persist(issue);
    }
    for (var value : files) {
      var file = new ImportFileEntity();
      file.id = UUID.randomUUID();
      file.design = design;
      file.role = value.role();
      file.filename = value.filename();
      file.sha256 = value.sha256();
      file.content = value.content();
      em.persist(file);
    }
    em.flush();
    return design.id;
  }

  // 설계 ID로 모든 조회를 제한하고 fetch join으로 필요한 LAZY 관계를 트랜잭션 안에서 읽는다.
  @Transactional(readOnly = true)
  public CircuitDesign load(UUID id) {
    var design =
        repository
            .findById(id)
            .orElseThrow(() -> new NoSuchElementException("Design not found: " + id));
    var componentRows =
        em.createQuery(
                "select c from ComponentInstanceEntity c join fetch c.specification where c.design.id = :id",
                ComponentInstanceEntity.class)
            .setParameter("id", id)
            .getResultList();
    Map<String, ComponentInstance> components = new LinkedHashMap<>();
    Map<UUID, PartSpecification> specs = new LinkedHashMap<>();
    // 미참조 사양까지 design-spec 링크에서 복원해 모델의 사양 집합을 잃지 않는다.
    for (var spec : design.specifications) specs.put(spec.id, toModel(spec));
    for (var row : componentRows) {
      var spec = toModel(row.specification);
      specs.put(spec.id(), spec);
      components.put(
          row.reference,
          new ComponentInstance(
              row.reference,
              row.value,
              spec.id(),
              row.rawSources,
              row.sourcePath,
              row.assemblyStatus));
    }
    var pins =
        em
            .createQuery(
                "select p from ComponentPinEntity p join fetch p.component where p.component.design.id = :id",
                ComponentPinEntity.class)
            .setParameter("id", id)
            .getResultList()
            .stream()
            .map(
                p ->
                    new ComponentPin(
                        key(p), p.name, p.electricalType, p.disposition, p.sourceMetadata))
            .toList();
    var nets = new LinkedHashMap<String, CircuitNet>();
    for (var row :
        em.createQuery(
                "select n from CircuitNetEntity n where n.design.id = :id", CircuitNetEntity.class)
            .setParameter("id", id)
            .getResultList())
      nets.put(row.netCode, new CircuitNet(row.netCode, row.name, row.sourcePath));
    var connections =
        em
            .createQuery(
                "select c from PinConnectionEntity c join fetch c.pin p join fetch p.component join fetch c.net where c.design.id = :id",
                PinConnectionEntity.class)
            .setParameter("id", id)
            .getResultList()
            .stream()
            .map(c -> new PinConnection(key(c.pin), c.net.netCode))
            .toList();
    var issues =
        em
            .createQuery(
                "select i from ImportIssueEntity i where i.design.id = :id order by i.issueOrder",
                ImportIssueEntity.class)
            .setParameter("id", id)
            .getResultList()
            .stream()
            .map(i -> new ImportIssue(i.severity, i.code, i.reference, i.source, i.message))
            .toList();
    return new CircuitDesign(
        id,
        design.name,
        design.version,
        components,
        specs,
        pins,
        nets,
        connections,
        issues,
        design.sourceMetadata);
  }

  @Transactional(readOnly = true)
  public List<SourceFile> sourceFiles(UUID id) {
    if (!repository.existsById(id)) throw new NoSuchElementException("Design not found: " + id);
    return em
        .createQuery(
            "select f from ImportFileEntity f where f.design.id = :id order by f.role",
            ImportFileEntity.class)
        .setParameter("id", id)
        .getResultList()
        .stream()
        .map(f -> new SourceFile(f.role, f.filename, f.content))
        .toList();
  }

  private static PinKey key(ComponentPinEntity p) {
    return new PinKey(p.component.reference, p.number);
  }

  private static PartSpecification toModel(PartSpecificationEntity p) {
    return new PartSpecification(p.id, p.manufacturer, p.mpn, p.type, p.attributes);
  }
}
