package com.circuitagent.design;

// QcvFixtures: 실제 공개 Fixture의 회로 사실과 파싱 결과를 검증한다.

import java.io.IOException;
import java.nio.file.*;

final class QcvFixtures {
  static Path dir() {
    return Path.of(System.getProperty("qcv.fixture.dir"));
  }

  static byte[] file(String board, String extension) throws IOException {
    Path path = dir().resolve(board + extension);
    if (!Files.exists(path))
      throw new IOException(
          "Missing licensed-local fixture: run python3 scripts/fetch-qcv-fixtures.py and ./gradlew convertQcvBoms: "
              + path);
    return Files.readAllBytes(path);
  }
}
