package com.circuitagent.design.api;

import com.circuitagent.design.graph.CircuitAssembler;
import com.circuitagent.design.model.CircuitDesign;
import com.circuitagent.design.parse.*;
import com.circuitagent.design.persistence.*;
import java.io.IOException;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * 업로드 → BOM/Netlist 파싱 → Reference 결합 → MySQL 저장을 조율하는 서비스. Spring이 주입한 Parser들을 format으로 등록하여
 * QCV/KiCad를 공통 모델로 연결한다. 파싱 실패는 안전한 입력 오류로 변환하고 DB 실패는 저장 트랜잭션이 롤백한다.
 */
@Service
public class DesignImportService {
  private final BomParser bomParser;
  private final Map<String, NetlistParser> netlistParsers;
  private final CircuitAssembler assembler;
  private final CircuitDesignStore store;

  public DesignImportService(
      BomParser bomParser,
      List<NetlistParser> netlistParsers,
      CircuitAssembler assembler,
      CircuitDesignStore store) {
    this.bomParser = bomParser;
    this.assembler = assembler;
    this.store = store;
    // 새 EDA 어댑터는 NetlistParser bean만 추가하면 format 키로 선택할 수 있다.
    var parsers = new HashMap<String, NetlistParser>();
    for (var parser : netlistParsers) parsers.put(parser.format(), parser);
    this.netlistParsers = Map.copyOf(parsers);
  }

  public CircuitDesign importDesign(
      String name, String version, SourceFile bom, SourceFile netlist, String format) {
    String extension = netlist.filename().toLowerCase(Locale.ROOT);
    if (!extension.endsWith(".net") && !extension.endsWith(".qcv"))
      throw new IllegalArgumentException("Netlist must use .net or .qcv extension");
    // qcv 확장자는 QCV로 선택한다. net은 명시 format/default KiCad 계약을 따른다.
    String parserFormat =
        netlist.filename().toLowerCase(Locale.ROOT).endsWith(".qcv")
            ? "qcv"
            : format.toLowerCase(Locale.ROOT);
    var parser = netlistParsers.get(parserFormat);
    if (parser == null)
      throw new IllegalArgumentException("Unsupported Netlist format: " + parserFormat);
    if (bom.content().length == 0 || netlist.content().length == 0)
      throw new IllegalArgumentException("Both input files must be nonempty");
    String source = bom.filename();
    CircuitDesign design;
    // 파싱의 현재 파일명을 추적해 사용자에게 어느 입력을 확인할지 알려 준다.
    try {
      var bomResult = bomParser.parse(bom.content(), bom.filename());
      source = netlist.filename();
      var netResult = parser.parse(netlist.content(), netlist.filename());
      if (bomResult.components().isEmpty() || netResult.components().isEmpty())
        throw new IllegalArgumentException("Both input files must contain components");
      design = assembler.assemble(name, version, bomResult, netResult);
    } catch (IOException | IllegalArgumentException e) {
      throw new ImportRejectedException(
          source, "Input parsing failed; verify file format, structure and parser limits", e);
    }
    // 두 원본과 완성 모델을 함께 저장한다. 저장 성공 전에는 성공 응답을 반환하지 않는다.
    store.save(design, List.of(bom, netlist));
    return design;
  }
}
