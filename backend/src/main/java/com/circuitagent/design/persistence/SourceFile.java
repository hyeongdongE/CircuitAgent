package com.circuitagent.design.persistence;

import java.security.*;
import java.util.HexFormat;

/**
 * Import에 사용한 원본 파일과 SHA256. bytes를 방어적으로 복사해 외부에서 원본을 바꿀 수 없게 한다. BOM/Netlist 역할별로 저장하며
 * sourceFiles는 재현·검토용 Java 조회이고 공개 다운로드 API가 아니다.
 */
public record SourceFile(String role, String filename, byte[] content) {
  public SourceFile {
    if (!"bom".equals(role) && !"netlist".equals(role))
      throw new IllegalArgumentException("Invalid source role");
    if (filename == null || filename.isBlank())
      throw new IllegalArgumentException("Filename is required");
    content = content.clone();
  }

  @Override
  public byte[] content() {
    return content.clone();
  }

  public String sha256() {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
