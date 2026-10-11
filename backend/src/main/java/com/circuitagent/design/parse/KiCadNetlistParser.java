package com.circuitagent.design.parse;

import com.circuitagent.design.model.*;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.circuitagent.design.model.ImportIssue.*;
import static com.circuitagent.design.parse.SExpression.Node;

@Component
public class KiCadNetlistParser implements NetlistParser {
    @Override public String format() { return "kicad"; }
    @Override public NetlistResult parse(byte[] bytes, String filename) throws IOException {
        var root = SExpression.parse(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString());
        if (!root.tag().equals("export")) throw new IllegalArgumentException("Expected KiCad export root");
        var compRoot = required(root, "components");
        var netRoot = required(root, "nets");
        var metadata = new LinkedHashMap<String, String>();
        metadata.put("format", "kicad"); metadata.put("filename", filename);
        root.child("design").ifPresent(d -> {
            for (String key : List.of("source", "date", "tool")) if (d.field(key) != null) metadata.put(key, d.field(key));
        });
        Map<String, List<Node>> libraries = new HashMap<>();
        root.child("libparts").ifPresent(parts -> {
            for (var part : parts.children("libpart")) {
                var pins = part.child("pins").map(p -> p.children("pin")).orElse(List.of());
                libraries.put(libKey(part.field("lib"), part.field("part")), pins);
                part.child("aliases").ifPresent(aliases -> {
                    for (var alias : aliases.children("alias")) libraries.put(libKey(part.field("lib"), alias.scalar()), pins);
                });
            }
        });
        Map<String, ComponentInstance> components = new LinkedHashMap<>();
        Map<UUID, PartSpecification> specs = new LinkedHashMap<>();
        Map<PinKey, ComponentPin> pins = new LinkedHashMap<>();
        Map<String, CircuitNet> nets = new LinkedHashMap<>();
        Map<PinKey, PinConnection> connections = new LinkedHashMap<>();
        Set<PinKey> ambiguous = new HashSet<>();
        List<ImportIssue> issues = new ArrayList<>();
        for (var comp : compRoot.children("comp")) {
            String ref = field(comp, "ref");
            if (components.containsKey(ref)) {
                issues.add(new ImportIssue(Severity.ERROR, Code.DUPLICATE_REFERENCE, ref, filename, "Duplicate Netlist Reference; first instance retained"));
                continue;
            }
            var raw = new LinkedHashMap<String, String>();
            for (String key : List.of("value", "footprint", "datasheet", "description", "tstamps")) if (comp.field(key) != null) raw.put(key, comp.field(key));
            comp.child("fields").ifPresent(f -> {
                for (var entry : f.children("field")) {
                    String name = entry.field("name");
                    if (name != null && entry.values().getLast() instanceof SExpression.Atom a && entry.values().size() > 2) raw.put("field:" + name, a.text());
                }
            });
            String path = comp.child("sheetpath").map(p -> p.field("names")).orElse(null);
            if (path != null) raw.put("sheetpath", path);
            Node lib = comp.child("libsource").orElse(null);
            if (lib != null) {
                if (lib.field("lib") != null) raw.put("library", lib.field("lib"));
                if (lib.field("part") != null) raw.put("libraryPart", lib.field("part"));
                for (var pin : libraries.getOrDefault(libKey(lib.field("lib"), lib.field("part")), List.of())) {
                    var key = new PinKey(ref, field(pin, "num"));
                    pins.put(key, new ComponentPin(key, pin.field("name"), pin.field("type")));
                }
            }
            var spec = new PartSpecification(UUID.randomUUID(), null, null, null, Map.of());
            specs.put(spec.id(), spec);
            components.put(ref, new ComponentInstance(ref, comp.field("value"), spec.id(), Map.of("netlist", raw), path));
        }
        for (var net : netRoot.children("net")) {
            String id = field(net, "code");
            String name = field(net, "name");
            if (nets.putIfAbsent(id, new CircuitNet(id, name, metadata.get("source"))) != null) {
                issues.add(new ImportIssue(Severity.ERROR, Code.DUPLICATE_NET, null, filename, "Duplicate Net code " + id));
                continue;
            }
            for (var node : net.children("node")) {
                String ref = field(node, "ref");
                if (!components.containsKey(ref)) {
                    issues.add(new ImportIssue(Severity.ERROR, Code.UNKNOWN_COMPONENT, ref, filename, "Net node references missing component"));
                    continue;
                }
                var key = new PinKey(ref, field(node, "pin"));
                var existing = pins.get(key);
                String pinName = node.field("pinfunction");
                String pinType = node.field("pintype");
                pins.put(key, new ComponentPin(key, pinName != null ? pinName : existing == null ? null : existing.name(),
                    pinType != null ? pinType : existing == null ? null : existing.electricalType()));
                var previous = connections.get(key);
                if (ambiguous.contains(key) || previous != null && !previous.netId().equals(id)) {
                    ambiguous.add(key); connections.remove(key);
                    issues.add(new ImportIssue(Severity.ERROR, Code.AMBIGUOUS_CONNECTION, ref, filename,
                        "Pin " + key.number() + " assigned to multiple Nets; connection omitted"));
                } else connections.putIfAbsent(key, new PinConnection(key, id));
            }
        }
        return new NetlistResult(components, specs, List.copyOf(pins.values()), nets, List.copyOf(connections.values()), issues, metadata);
    }
    private static Node required(Node parent, String tag) { return parent.child(tag).orElseThrow(() -> new IllegalArgumentException("Missing KiCad " + tag)); }
    private static String field(Node node, String key) {
        String value = node.field(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing KiCad " + node.tag() + "/" + key);
        return value;
    }
    private static String libKey(String lib, String part) { return lib + "\u0000" + part; }
}
