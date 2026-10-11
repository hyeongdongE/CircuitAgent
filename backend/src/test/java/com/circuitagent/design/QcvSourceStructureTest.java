package com.circuitagent.design;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import static org.assertj.core.api.Assertions.*;

class QcvSourceStructureTest {
    @Test void realFeStructureSeparatesNetConnectionsPlainPinsAndTerminations() throws Exception {
        String source = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(QcvFixtures.file("fe", ".qcv"))).toString();
        var net = Pattern.compile("NET\\s*:\\s*'([^']+)'\\s+(.+)");
        Set<String> names = new HashSet<>(), connections = new HashSet<>(); int separate=0,term=0,plain=0;
        for (String line : source.lines().toList()) {
            if (line.isBlank() || line.startsWith("#")) continue;
            var match = net.matcher(line);
            if (match.matches()) {
                assertThat(names.add(match.group(1))).isTrue();
                connections.addAll(List.of(match.group(2).split("\\s+")));
                if (match.group(1).equals("U16_EN")) assertThat(match.group(2).split("\\s+")).containsExactly("R107-2","R83-2","U16-5");
            } else {
                assertThat(line).startsWith("PIN : '"); separate++;
                if (line.endsWith("(by TERM)")) term++; else plain++;
            }
        }
        assertThat(names).hasSize(335); assertThat(connections).hasSize(1500);
        assertThat(separate).isEqualTo(143); assertThat(term).isEqualTo(135); assertThat(plain).isEqualTo(8);
    }
}
