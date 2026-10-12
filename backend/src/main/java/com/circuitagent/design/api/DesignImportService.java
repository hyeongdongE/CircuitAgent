package com.circuitagent.design.api;

import com.circuitagent.design.graph.CircuitAssembler;
import com.circuitagent.design.model.CircuitDesign;
import com.circuitagent.design.parse.*;
import com.circuitagent.design.persistence.*;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.util.*;

@Service
public class DesignImportService {
    private final BomParser bomParser;
    private final Map<String, NetlistParser> netlistParsers;
    private final CircuitAssembler assembler;
    private final CircuitDesignStore store;
    public DesignImportService(BomParser bomParser, List<NetlistParser> netlistParsers, CircuitAssembler assembler, CircuitDesignStore store) {
        this.bomParser = bomParser; this.assembler = assembler; this.store = store;
        var parsers = new HashMap<String, NetlistParser>();
        for (var parser : netlistParsers) parsers.put(parser.format(), parser);
        this.netlistParsers = Map.copyOf(parsers);
    }
    public CircuitDesign importDesign(String name, String version, SourceFile bom, SourceFile netlist, String format) {
        String extension = netlist.filename().toLowerCase(Locale.ROOT);
        if (!extension.endsWith(".net") && !extension.endsWith(".qcv")) throw new IllegalArgumentException("Netlist must use .net or .qcv extension");
        String parserFormat = netlist.filename().toLowerCase(Locale.ROOT).endsWith(".qcv") ? "qcv" : format.toLowerCase(Locale.ROOT);
        var parser = netlistParsers.get(parserFormat);
        if (parser == null) throw new IllegalArgumentException("Unsupported Netlist format: " + parserFormat);
        if (bom.content().length == 0 || netlist.content().length == 0) throw new IllegalArgumentException("Both input files must be nonempty");
        String source = bom.filename();
        CircuitDesign design;
        try {
            var bomResult = bomParser.parse(bom.content(), bom.filename());
            source = netlist.filename();
            var netResult = parser.parse(netlist.content(), netlist.filename());
            if (bomResult.components().isEmpty() || netResult.components().isEmpty()) throw new IllegalArgumentException("Both input files must contain components");
            design = assembler.assemble(name, version, bomResult, netResult);
        } catch (IOException | IllegalArgumentException e) {
            throw new ImportRejectedException(source, "Input parsing failed; verify file format, structure and parser limits", e);
        }
        store.save(design, List.of(bom, netlist));
        return design;
    }
}
