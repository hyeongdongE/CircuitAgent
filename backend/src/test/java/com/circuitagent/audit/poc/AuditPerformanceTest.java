package com.circuitagent.audit.poc;

import com.circuitagent.MySqlIntegrationSupport;
import com.circuitagent.design.graph.*;
import com.circuitagent.design.model.*;
import com.circuitagent.design.persistence.CircuitDesignStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Measurements, not an SLA. Repeated rules/components below are explicitly synthetic load. */
@AutoConfigureMockMvc
class AuditPerformanceTest extends MySqlIntegrationSupport {
    @Autowired AuditRunner runner;
    @Autowired AuditStore audits;
    @Autowired CircuitDesignStore designs;
    @Autowired DesignQueryService queries;
    @Autowired PocRuleCatalog catalog;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mvc;

    @Test void measuresActualSnapshotAndScaledRulesWithoutRestoringPerRule() throws Exception {
        var real=SnapshotContractTest.real();designs.save(real.designSnapshot(),List.of());
        // Warm JVM/JPA paths; excluded from samples.
        runner.run(queries.snapshot(real.designId()),catalog.rules());
        var report=new LinkedHashMap<String,Object>();
        report.put("environment",Map.of("java",System.getProperty("java.version"),"os",System.getProperty("os.name"),"processors",Runtime.getRuntime().availableProcessors(),"maxHeapBytes",Runtime.getRuntime().maxMemory(),"mysql","8.0.43 Docker","samples",3,"api","MockMvc, real MySQL, no network/browser overhead"));
        var scenarios=new ArrayList<Map<String,Object>>();
        for(int count:List.of(6,60)) {
            var rules=count==6?catalog.rules():scaledRules(count);
            var samples=new ArrayList<Map<String,Double>>();
            for(int i=0;i<3;i++) {
                long start=System.nanoTime();var graph=queries.snapshot(real.designId());double load=ms(start);
                var execution=runner.run(graph,rules);assertThat(execution.results()).hasSize(count);
                var times=new LinkedHashMap<>(execution.timingMs());times.put("snapshotLoadMs",load);
                start=System.nanoTime();audits.save(execution);times.put("auditPersistenceMs",ms(start));
                start=System.nanoTime();mvc.perform(get("/api/designs/"+real.designId()+"/audits/"+execution.id())).andExpect(status().isOk());times.put("auditApiResponseMs",ms(start));
                samples.add(times);
            }
            scenarios.add(Map.of("source",count==6?"Actual Sparrow FE R3 / six reviewed rules":"Actual Sparrow FE R3 / 60 synthetic repetitions of nominal C5 check","components",real.designSnapshot().components().size(),"rules",count,"samplesMs",samples,"statisticsMs",statistics(samples)));
        }
        report.put("mysqlScenarios",scenarios);
        var componentScenarios=new ArrayList<Map<String,Object>>();
        for(int count:List.of(100,1000,5000)) {
            var d=real.designSnapshot();var components=new HashMap<>(d.components());
            var base=real.getComponent("C5");
            for(int i=0;i<count;i++){String ref="SYNTH"+i;components.put(ref,new ComponentInstance(ref,base.value(),base.partSpecificationId(),Map.of(),null,AssemblyStatus.FITTED));}
            var synthetic=new CircuitDesign(UUID.randomUUID(),"Synthetic component scaling","benchmark",components,d.specifications(),d.pins(),d.nets(),d.connections(),d.issues(),Map.of("scope","Synthetic, no added physical connectivity; no DB persistence"));
            var samples=new ArrayList<Map<String,Double>>();
            for(int i=0;i<3;i++){long start=System.nanoTime();var graph=new CircuitGraph(synthetic);double construction=ms(start);var execution=runner.run(graph,catalog.rules());var times=new LinkedHashMap<>(execution.timingMs());times.put("graphConstructionMs",construction);samples.add(times);}
            componentScenarios.add(Map.of("components",components.size(),"additionalSyntheticComponents",count,"rules",6,"samplesMs",samples,"statisticsMs",statistics(samples)));
        }
        report.put("syntheticComponentScenarios",componentScenarios);
        Files.writeString(Path.of(System.getProperty("qcv.fixture.dir")).resolve("audit-performance.json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }
    private List<AuditRule> scaledRules(int count){var r=catalog.select(List.of("tps54302-c5-nominal")).getFirst();var out=new ArrayList<AuditRule>();for(int i=0;i<count;i++)out.add(new AuditRule("synthetic-load-"+i,r.version(),r.type(),r.target(),r.condition(),r.applicable(),r.notApplicableReason(),r.attribute(),r.unit(),r.expected(),r.expectedNet(),r.divider(),r.source()));return out;}
    private static double ms(long start){return (System.nanoTime()-start)/1_000_000.0;}
    private static Map<String,Object> statistics(List<Map<String,Double>> samples){var out=new LinkedHashMap<String,Object>();for(var key:samples.getFirst().keySet()){var values=samples.stream().mapToDouble(s->s.get(key)).sorted().toArray();out.put(key,Map.of("median",values[values.length/2],"mean",Arrays.stream(values).average().orElseThrow()));}return out;}
}
