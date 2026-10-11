package com.circuitagent;

// FixtureIntegrityTest: 실제 공개 Fixture의 회로 사실과 파싱 결과를 검증한다.

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class FixtureIntegrityTest {
  @Test
  void originalPublicFilesMatchPinnedSourceChecksums() throws Exception {
    var manifest =
        new ObjectMapper().readTree(getClass().getResourceAsStream("/fixtures/sources.json"));
    assertThat(manifest.size()).isEqualTo(4);
    for (var entry : manifest) {
      try (var input = getClass().getResourceAsStream("/fixtures/" + entry.get("file").asText())) {
        assertThat(input).isNotNull();
        byte[] bytes = input.readAllBytes();
        assertThat(bytes.length).isEqualTo(entry.get("bytes").asInt());
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
            .isEqualTo(entry.get("sha256").asText());
        assertThat(
                StandardCharsets.UTF_8
                    .newDecoder()
                    .decode(java.nio.ByteBuffer.wrap(bytes))
                    .length())
            .isPositive();
      }
    }
  }
}
