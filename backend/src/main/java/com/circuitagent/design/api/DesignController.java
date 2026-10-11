package com.circuitagent.design.api;

import com.circuitagent.design.graph.CircuitGraph;
import com.circuitagent.design.model.*;
import com.circuitagent.design.persistence.*;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.net.URI;
import java.util.*;

@RestController
@RequestMapping("/api/designs")
@Validated
public class DesignController {
    public record ImportResponse(UUID id, int componentCount, int pinCount, int netCount, List<ImportIssue> issues) {}
    public record PinView(String number, String name, String electricalType, CircuitNet net) {}
    public record ComponentView(String reference, String value, PartSpecification specification, List<PinView> pins,
                                Map<String, Map<String, String>> rawSources, String sourcePath) {}
    public record NetView(String id, String name, String sourcePath, List<PinKey> connectedPins, Set<String> components) {}
    private final DesignImportService imports;
    private final CircuitDesignStore store;
    public DesignController(DesignImportService imports, CircuitDesignStore store) { this.imports = imports; this.store = store; }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImportResponse> importDesign(@RequestParam @NotBlank @Size(max = 191) String name,
        @RequestParam(defaultValue = "v1") @NotBlank @Size(max = 64) String version,
        @RequestParam(defaultValue = "kicad") String netlistFormat,
        @RequestPart MultipartFile bom, @RequestPart MultipartFile netlist) throws IOException {
        var design = imports.importDesign(name, version, file("bom", bom), file("netlist", netlist), netlistFormat);
        return ResponseEntity.created(URI.create("/api/designs/" + design.id()))
            .body(new ImportResponse(design.id(), design.components().size(), design.pins().size(), design.nets().size(), design.issues()));
    }
    @GetMapping("/{id}") public CircuitDesign design(@PathVariable UUID id) { return store.load(id); }
    @GetMapping("/{id}/components") public List<ComponentView> components(@PathVariable UUID id) {
        var design = store.load(id); var graph = new CircuitGraph(design);
        return design.components().keySet().stream().sorted().map(ref -> view(graph, ref)).toList();
    }
    @GetMapping("/{id}/components/{ref}") public ComponentView component(@PathVariable UUID id, @PathVariable String ref) {
        return view(new CircuitGraph(store.load(id)), ref);
    }
    @GetMapping("/{id}/components/{ref}/pins") public List<PinView> pins(@PathVariable UUID id, @PathVariable String ref) {
        return component(id, ref).pins();
    }
    @GetMapping("/{id}/nets") public List<NetView> nets(@PathVariable UUID id) {
        var design = store.load(id); var graph = new CircuitGraph(design);
        return design.nets().values().stream().sorted(Comparator.comparing(CircuitNet::id)).map(net -> netView(graph, net)).toList();
    }
    @GetMapping("/{id}/nets/{netId}") public NetView net(@PathVariable UUID id, @PathVariable String netId) {
        var design = store.load(id); var net = design.nets().get(netId);
        if (net == null) throw new NoSuchElementException("Net not found: " + netId);
        return netView(new CircuitGraph(design), net);
    }
    @GetMapping("/{id}/issues") public List<ImportIssue> issues(@PathVariable UUID id) { return store.load(id).issues(); }
    private static ComponentView view(CircuitGraph graph, String ref) {
        var component = graph.getComponent(ref);
        var pins = graph.getComponentPins(ref).stream().sorted(Comparator.comparing(p -> p.key().number()))
            .map(pin -> new PinView(pin.key().number(), pin.name(), pin.electricalType(), graph.getNetOfPin(ref, pin.key().number()).orElse(null))).toList();
        return new ComponentView(ref, component.value(), graph.getComponentSpecification(ref), pins, component.rawSources(), component.sourcePath());
    }
    private static NetView netView(CircuitGraph graph, CircuitNet net) {
        var refs = new TreeSet<String>();
        graph.getComponentsOnNet(net.id()).forEach(c -> refs.add(c.reference()));
        return new NetView(net.id(), net.name(), net.sourcePath(), graph.getConnectedPins(net.id()).stream().map(ComponentPin::key).toList(), refs);
    }
    private static SourceFile file(String role, MultipartFile file) throws IOException {
        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank() || filename.length() > 512) throw new IllegalArgumentException("Valid input filename required");
        if (file.getSize() > 10 * 1024 * 1024) throw new org.springframework.web.multipart.MaxUploadSizeExceededException(10 * 1024 * 1024);
        return new SourceFile(role, filename, file.getBytes());
    }
}
