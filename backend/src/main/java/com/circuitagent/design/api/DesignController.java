package com.circuitagent.design.api;

import com.circuitagent.design.graph.CircuitGraph;
import com.circuitagent.design.model.*;
import com.circuitagent.design.persistence.*;
import jakarta.validation.constraints.*;
import java.io.IOException;
import java.net.URI;
import java.util.*;
import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * Stage 02 REST 경계. multipart 파일을 받아 서비스에 전달하고 조회 결과를 DTO로 반환한다. 파일명은 basename으로 정리하며 원본 경로를 파일 시스템
 * 경로로 사용하지 않는다. 설계 ID로 데이터를 구분하지만 사용자 인증/소유권 검사는 별도로 필요하다.
 */
@RestController
@RequestMapping("/api/designs")
@Validated
public class DesignController {
  // API DTO는 저장 Entity와 분리한다. rawSources/sourceMetadata는 원문 근거이므로 민감할 수 있다.
  public record ImportResponse(
      UUID id,
      int componentCount,
      int pinCount,
      int netCount,
      List<ImportIssue> issues,
      int connectionCount,
      int terminatedPinCount) {}

  public record PinView(
      String number,
      String name,
      String electricalType,
      CircuitNet net,
      PinDisposition disposition,
      Map<String, String> sourceMetadata) {}

  public record ComponentView(
      String reference,
      String value,
      PartSpecification specification,
      List<PinView> pins,
      Map<String, Map<String, String>> rawSources,
      String sourcePath,
      AssemblyStatus assemblyStatus) {}

  public record NetView(
      String id,
      String name,
      String sourcePath,
      List<PinKey> connectedPins,
      Set<String> components) {}

  private final DesignImportService imports;
  private final CircuitDesignStore store;

  public DesignController(DesignImportService imports, CircuitDesignStore store) {
    this.imports = imports;
    this.store = store;
  }

  @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<ImportResponse> importDesign(
      @RequestParam @NotBlank @Size(max = 191) String name,
      @RequestParam(defaultValue = "v1") @NotBlank @Size(max = 64) String version,
      @RequestParam(defaultValue = "kicad") String netlistFormat,
      @RequestPart MultipartFile bom,
      @RequestPart MultipartFile netlist)
      throws IOException {
    var design =
        imports.importDesign(
            name, version, file("bom", bom), file("netlist", netlist), netlistFormat);
    return ResponseEntity.created(URI.create("/api/designs/" + design.id()))
        .body(
            new ImportResponse(
                design.id(),
                design.components().size(),
                design.pins().size(),
                design.nets().size(),
                design.issues(),
                design.connections().size(),
                (int)
                    design.pins().stream()
                        .filter(p -> p.disposition() == PinDisposition.TERMINATED)
                        .count()));
  }

  @GetMapping("/{id}")
  public CircuitDesign design(@PathVariable UUID id) {
    return store.load(id);
  }

  @GetMapping("/{id}/components")
  public List<ComponentView> components(@PathVariable UUID id) {
    var design = store.load(id);
    var graph = new CircuitGraph(design);
    return design.components().keySet().stream().sorted().map(ref -> view(graph, ref)).toList();
  }

  @GetMapping("/{id}/components/{ref}")
  public ComponentView component(@PathVariable UUID id, @PathVariable String ref) {
    return view(new CircuitGraph(store.load(id)), ref);
  }

  @GetMapping("/{id}/components/{ref}/pins")
  public List<PinView> pins(@PathVariable UUID id, @PathVariable String ref) {
    return component(id, ref).pins();
  }

  @GetMapping("/{id}/nets")
  public List<NetView> nets(@PathVariable UUID id) {
    var design = store.load(id);
    var graph = new CircuitGraph(design);
    return design.nets().values().stream()
        .sorted(Comparator.comparing(CircuitNet::id))
        .map(net -> netView(graph, net))
        .toList();
  }

  @GetMapping("/{id}/nets/{netId}")
  public NetView net(@PathVariable UUID id, @PathVariable String netId) {
    var design = store.load(id);
    var net = design.nets().get(netId);
    if (net == null) throw new NoSuchElementException("Net not found: " + netId);
    return netView(new CircuitGraph(design), net);
  }

  @GetMapping("/{id}/components/{ref}/pins/{number}")
  public CircuitGraph.PinContext pinContext(
      @PathVariable UUID id, @PathVariable String ref, @PathVariable String number) {
    return new CircuitGraph(store.load(id)).getPinContext(ref, number);
  }

  @GetMapping("/{id}/issues")
  public List<ImportIssue> issues(@PathVariable UUID id) {
    return store.load(id).issues();
  }

  private static ComponentView view(CircuitGraph graph, String ref) {
    var component = graph.getComponent(ref);
    var pins =
        graph.getComponentPins(ref).stream()
            .sorted(Comparator.comparing(p -> p.key().number()))
            .map(
                pin ->
                    new PinView(
                        pin.key().number(),
                        pin.name(),
                        pin.electricalType(),
                        graph.getNetOfPin(ref, pin.key().number()).orElse(null),
                        pin.disposition(),
                        pin.sourceMetadata()))
            .toList();
    return new ComponentView(
        ref,
        component.value(),
        graph.getComponentSpecification(ref),
        pins,
        component.rawSources(),
        component.sourcePath(),
        component.assemblyStatus());
  }

  private static NetView netView(CircuitGraph graph, CircuitNet net) {
    var refs = new TreeSet<String>();
    graph.getComponentsOnNet(net.id()).forEach(c -> refs.add(c.reference()));
    return new NetView(
        net.id(),
        net.name(),
        net.sourcePath(),
        graph.getConnectedPins(net.id()).stream().map(ComponentPin::key).toList(),
        refs);
  }

  // 브라우저가 보낸 경로는 신뢰하지 않고 파일명만 받는다. 업로드 한도는 servlet 설정과 여기에서 검사한다.
  private static SourceFile file(String role, MultipartFile file) throws IOException {
    String filename = file.getOriginalFilename();
    if (filename == null || filename.isBlank() || filename.length() > 512)
      throw new IllegalArgumentException("Valid input filename required");
    filename = filename.replace('\\', '/');
    filename = filename.substring(filename.lastIndexOf('/') + 1);
    if (filename.isBlank() || filename.chars().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("Valid input filename required");
    if (file.getSize() > 10 * 1024 * 1024)
      throw new org.springframework.web.multipart.MaxUploadSizeExceededException(10 * 1024 * 1024);
    return new SourceFile(role, filename, file.getBytes());
  }
}
