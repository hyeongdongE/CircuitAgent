package com.circuitagent.audit.poc;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class AuditResultTest {
    @Test void preservesFiveDifferentStatesEvidenceMissingInputsAndRealZero(){
        var e=new AuditEvidence(RuleInputTest.SOURCE,"synthetic",Map.of(),Map.of(),List.of(),Map.of("formula","fixture"),List.of());
        for(var s:AuditStatus.values()){
            var r=new AuditResult(UUID.randomUUID(),"rule","v1","R1",s,BigDecimal.ZERO,null,"Ω","Explicit reason",s==AuditStatus.UNKNOWN?List.of("MPN"):List.of(),e,Instant.now());
            assertThat(r.status()).isEqualTo(s);assertThat(r.measured()).isEqualByComparingTo("0");assertThat(r.evidence().datasheet()).isEqualTo(RuleInputTest.SOURCE);
            assertThatThrownBy(()->r.missingInputs().add("x")).isInstanceOf(UnsupportedOperationException.class);
        }
        assertThatThrownBy(()->new AuditResult(UUID.randomUUID(),"r","v","R1",AuditStatus.UNKNOWN,null,null,null,"reason",List.of(),e,Instant.now())).isInstanceOf(IllegalArgumentException.class);
    }
}
