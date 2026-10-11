package com.circuitagent.audit.poc;
import com.circuitagent.design.graph.*;
import com.circuitagent.design.model.*;
import com.circuitagent.design.parse.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class RuleCheckerTest {
    static CircuitGraph own(String bom,String net) throws Exception {
        return new CircuitGraph(new CircuitAssembler().assemble("Own synthetic fixture","test",new BomParser().parse(bom.getBytes(StandardCharsets.UTF_8),"own.csv"),new QcvNetlistParser().parse(net.getBytes(StandardCharsets.UTF_8),"own.qcv")));
    }
    final DeterministicRuleChecker checker=new DeterministicRuleChecker();
    @Test void ownFixtureNominalValuePassAndFailWithUnitNormalization() throws Exception {var g=own("Reference,Value,DNP\nR1,1k,no\n","NET : 'x' R1-1\n");assertThat(checker.evaluate(g,RuleInputTest.range("a","Ω","900","1100")).status()).isEqualTo(AuditStatus.PASS);assertThat(checker.evaluate(g,RuleInputTest.range("b","Ω","2000","3000")).status()).isEqualTo(AuditStatus.FAIL);}
    @Test void ownFixtureMissingUnitsAssemblyAndValuesReturnUnknown() throws Exception {var g=own("Reference,Value,DNP\nR1,,no\n","PIN : '/R1' R1-1\n");assertThat(checker.evaluate(g,RuleInputTest.range("a","Ω","900","1100")).status()).isEqualTo(AuditStatus.UNKNOWN);var valid=own("Reference,Value,DNP\nR1,1k,no\n","NET : 'x' R1-1\n");assertThat(checker.evaluate(valid,RuleInputTest.range("a","F","900","1100")).missingInputs()).anyMatch(s->s.contains("unit"));}
    @Test void ownDnpTargetIsNotApplicableAndEngineFaultIsError() throws Exception {var g=own("Reference,Value,DNP\nR1,1k,yes\n","NET : 'x' R1-1\n");assertThat(checker.evaluate(g,RuleInputTest.range("a","Ω","900","1100")).status()).isEqualTo(AuditStatus.NOT_APPLICABLE);var broken=mock(CircuitGraph.class);when(broken.getComponent("R1")).thenThrow(new IllegalStateException("secret internal failure"));var result=checker.evaluate(broken,RuleInputTest.range("a","Ω","900","1100"));assertThat(result.status()).isEqualTo(AuditStatus.ERROR);assertThat(result.reason()).doesNotContain("secret");}
    @Test void actualEnWithDnpNeighborsIsUnknownAndPreservesBothSources() throws Exception {var catalog=new PocRuleCatalog(new ObjectMapper(),new StructuredRuleAdapter());var r=checker.evaluate(SnapshotContractTest.real(),catalog.select(List.of("tps54302-u16-en-network")).getFirst());assertThat(r.status()).isEqualTo(AuditStatus.UNKNOWN);assertThat(r.missingInputs()).anyMatch(s->s.contains("fitted neighbor"));assertThat(r.evidence().components()).containsKeys("U16","R107","R83");assertThat(r.evidence().pins().getFirst().net().name()).isEqualTo("U16_EN");}
    @Test void actualDividerReturnsCalculatedNominalButUnknownMissingConditions() throws Exception {var r=checker.evaluate(SnapshotContractTest.real(),new PocRuleCatalog(new ObjectMapper(),new StructuredRuleAdapter()).select(List.of("tps54302-u16-fb-nominal")).getFirst());assertThat(r.status()).isEqualTo(AuditStatus.UNKNOWN);assertThat(r.measured()).isBetween(new BigDecimal("11.96"),new BigDecimal("11.97"));assertThat(r.missingInputs()).anyMatch(s->s.contains("tolerance")).anyMatch(s->s.contains("expected"));assertThat(r.evidence().calculation()).containsEntry("outputNetLabelNotMeasuredVoltage","12V");}
    @Test void ownDividerChecksWorstCaseToleranceAndZeroDenominator() throws Exception {
        var g=own("Reference,Value,DNP\nU1,IC,no\nR1,10k,no\nR2,10k,no\n","NET : 'fb' U1-4 R1-1 R2-1\nNET : 'gnd' U1-1 R2-2\nNET : 'out' R1-2\n");
        var rule=new AuditRule("own-equation","test",AuditRule.Type.FB_DIVIDER,new AuditRule.Target("U1",null,"4","FB",false),"Synthetic equation fixture; explicitly supplied limits",true,null,null,"V",new AuditRule.Bounds(new BigDecimal("1.9"),new BigDecimal("2.1"),BigDecimal.ZERO),null,new AuditRule.Divider("R1","R2","1",BigDecimal.ONE,new BigDecimal("0.99"),new BigDecimal("1.01"),BigDecimal.ONE),RuleInputTest.SOURCE);
        assertThat(checker.evaluate(g,rule).status()).isEqualTo(AuditStatus.PASS);
        var bad=own("Reference,Value,DNP\nU1,IC,no\nR1,10k,no\nR2,0R,no\n","NET : 'fb' U1-4 R1-1 R2-1\nNET : 'gnd' U1-1 R2-2\nNET : 'out' R1-2\n");assertThat(checker.evaluate(bad,rule).status()).isEqualTo(AuditStatus.UNKNOWN);
    }
}
