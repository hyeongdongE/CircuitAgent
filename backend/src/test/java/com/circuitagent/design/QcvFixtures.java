package com.circuitagent.design;

import com.circuitagent.design.tools.CsvToXlsx;
import java.nio.file.*;
import java.io.IOException;

final class QcvFixtures {
    static Path dir() { return Path.of(System.getProperty("qcv.fixture.dir")); }
    static byte[] file(String board, String extension) throws IOException {
        Path path = dir().resolve(board + extension);
        if (!Files.exists(path)) throw new IOException("Missing licensed-local fixture: run python3 scripts/fetch-qcv-fixtures.py and ./gradlew convertQcvBoms: " + path);
        return Files.readAllBytes(path);
    }
}
