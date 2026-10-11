package com.circuitagent.design.parse;

import com.circuitagent.design.model.*;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.regex.Pattern;
import static com.circuitagent.design.model.ImportIssue.*;

/** Independent adapter for the line-based NET/PIN dialect observed in public Sparrow PADS exports. */
@Component
public class QcvNetlistParser implements NetlistParser {
    // Greedy reference + validated terminal suffix: preserve hierarchy/hyphens, never split every '-'.
    private static final Pattern TERMINAL = Pattern.compile("([/A-Za-z_][A-Za-z0-9_./()+:-]*)-([A-Za-z0-9_]+)");
    private static final Pattern RECORD = Pattern.compile("(NET|PIN)\\s*:\\s*'([^']+)'\\s+(.+)");
    @Override public String format() { return "qcv"; }
    @Override public NetlistResult parse(byte[] bytes, String filename) throws IOException {
        ParserLimits.file(bytes);
        String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        Map<String, ComponentInstance> components = new LinkedHashMap<>();
        Map<UUID, PartSpecification> specs = new LinkedHashMap<>();
        Map<PinKey, ComponentPin> pins = new LinkedHashMap<>();
        Map<String, CircuitNet> nets = new LinkedHashMap<>();
        Map<PinKey, PinConnection> connections = new LinkedHashMap<>();
        Set<PinKey> ambiguous = new HashSet<>();
        List<ImportIssue> issues = new ArrayList<>();
        int number = 0, separate = 0, terminated = 0, tokenCount = 0;
        String section = "unspecified";
        for (String original : text.lines().toList()) {
            int line = ++number;
            String value = original.trim();
            if (value.isBlank()) continue;
            if (value.startsWith("#")) { section = value.substring(1).trim(); continue; }
            if (line > ParserLimits.MAX_ROWS || value.length() > 100_000) throw syntax(filename, line, "QCV line budget exceeded");
            var record = RECORD.matcher(value);
            if (!record.matches()) throw syntax(filename, line, "Expected quoted NET or PIN record");
            String kind = record.group(1), label = record.group(2), tail = record.group(3).trim();
            boolean term = kind.equals("PIN") && tail.endsWith("(by TERM)");
            if (term) tail = tail.substring(0, tail.length() - "(by TERM)".length()).trim();
            String[] identifiers = tail.split("\\s+");
            if (tail.isBlank() || kind.equals("PIN") && identifiers.length != 1) throw syntax(filename, line, "PIN requires one terminal and only optional (by TERM)");
            CircuitNet net = null;
            if (kind.equals("NET")) {
                String id = id(label);
                net = new CircuitNet(id, label, filename + ":line:" + line);
                if (nets.putIfAbsent(id, net) != null) issues.add(new ImportIssue(Severity.WARNING, Code.DUPLICATE_NET, null,
                    filename + ":line:" + line, "Repeated NET name; terminal sets merged"));
            } else { separate++; if (term) terminated++; }
            for (String token : identifiers) {
                if (++tokenCount > ParserLimits.MAX_VALUES) throw syntax(filename, line, "QCV terminal budget exceeded");
                var terminal = TERMINAL.matcher(token);
                if (!terminal.matches()) throw syntax(filename, line, "Invalid terminal identifier");
                String ref = terminal.group(1), pinNumber = terminal.group(2);
                if (ref.length() > 191 || pinNumber.length() > 128) throw syntax(filename, line, "Terminal identifier exceeds database limits");
                if (!components.containsKey(ref)) {
                    var spec = new PartSpecification(UUID.randomUUID(), null, null, null, Map.of());
                    specs.put(spec.id(), spec);
                    components.put(ref, new ComponentInstance(ref, null, spec.id(), Map.of("netlist", Map.of("format", "qcv", "pinInventory", "observed-only")), filename + ":line:" + line));
                }
                var key = new PinKey(ref, pinNumber);
                var previousPin = pins.get(key);
                var source = new LinkedHashMap<String, String>(previousPin == null ? Map.of() : previousPin.sourceMetadata());
                source.put("filename", filename);
                source.put("record:" + line, kind + ": '" + label + "' " + token + (term ? " (by TERM)" : ""));
                source.put("section:" + line, section);
                PinDisposition disposition = kind.equals("NET") ? PinDisposition.UNSPECIFIED
                    : term ? PinDisposition.TERMINATED : section.equals("begin one pin nets list") ? PinDisposition.UNSPECIFIED : PinDisposition.UNCONNECTED;
                if (previousPin != null) {
                    boolean declaredBefore = previousPin.disposition() != PinDisposition.UNSPECIFIED;
                    if (kind.equals("PIN")) issues.add(new ImportIssue(Severity.WARNING, Code.DUPLICATE_CONNECTION, ref,
                        filename + ":line:" + line, "Repeated terminal declaration; one pin retained"));
                    if (declaredBefore && kind.equals("NET") || kind.equals("PIN") && connections.containsKey(key)
                        || declaredBefore && kind.equals("PIN") && previousPin.disposition() != disposition) {
                        ambiguous.add(key); connections.remove(key);
                        issues.add(ambiguity(ref, pinNumber, filename, line));
                    }
                    // Never silently replace a prior explicit declaration with unspecified NET metadata.
                    if (declaredBefore) disposition = previousPin.disposition();
                }
                pins.put(key, new ComponentPin(key, null, null, disposition, source));
                if (net != null && !ambiguous.contains(key)) {
                    var previous = connections.putIfAbsent(key, new PinConnection(key, net.id()));
                    if (previous != null) {
                        if (!previous.netId().equals(net.id())) {
                            ambiguous.add(key); connections.remove(key); issues.add(ambiguity(ref, pinNumber, filename, line));
                        } else issues.add(new ImportIssue(Severity.WARNING, Code.DUPLICATE_CONNECTION, ref,
                            filename + ":line:" + line, "Repeated terminal on same Net; connection deduplicated"));
                    }
                }
            }
        }
        if (pins.isEmpty()) throw syntax(filename, number, "QCV contains no terminals");
        return new NetlistResult(components, specs, List.copyOf(pins.values()), nets, List.copyOf(connections.values()), issues,
            Map.of("format", "qcv", "dialect", "public-pads-net-pin", "encoding", "UTF-8", "filename", filename,
                "separatePinRecords", Integer.toString(separate), "terminatedPinRecords", Integer.toString(terminated)));
    }
    private static ImportIssue ambiguity(String ref, String number, String filename, int line) {
        return new ImportIssue(Severity.ERROR, Code.AMBIGUOUS_CONNECTION, ref, "pin:" + number,
            "Pin " + number + " assigned to conflicting declarations; connection omitted at " + filename + ":line:" + line);
    }
    private static IllegalArgumentException syntax(String filename, int line, String message) {
        return new IllegalArgumentException(message + " at " + filename + ":line:" + line);
    }
    private static String id(String label) {
        try { return "qcv:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(label.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
