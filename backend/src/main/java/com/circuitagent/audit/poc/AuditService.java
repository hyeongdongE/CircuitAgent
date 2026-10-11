package com.circuitagent.audit.poc;
import com.circuitagent.design.graph.DesignQueryService;
import org.springframework.stereotype.Service;
import java.util.*;
@Service
public class AuditService {
    private final DesignQueryService queries;private final AuditRunner runner;private final AuditStore store;private final PocRuleCatalog catalog;private final StructuredRuleAdapter adapter;
    public AuditService(DesignQueryService q,AuditRunner r,AuditStore s,PocRuleCatalog c,StructuredRuleAdapter a){queries=q;runner=r;store=s;catalog=c;adapter=a;}
    public record Request(String schemaVersion,List<String> ruleIds,List<AuditRule> rules){}
    public AuditExecution execute(UUID designId,Request request) {
        if(request==null || !"poc-rule-v1".equals(request.schemaVersion()) || (request.ruleIds()!=null)==(request.rules()!=null))throw new IllegalArgumentException("Provide poc-rule-v1 and exactly one of ruleIds or rules");
        var rules=request.ruleIds()!=null?catalog.select(request.ruleIds()):adapter.validate(request.rules());
        long started=System.nanoTime();var graph=queries.snapshot(designId);double loadMs=(System.nanoTime()-started)/1_000_000.0;
        var result=runner.run(graph,rules);var timing=new HashMap<>(result.timingMs());timing.put("snapshotLoadMs",loadMs);
        var execution=new AuditExecution(result.id(),result.designId(),result.designVersion(),result.snapshotHash(),result.ruleSnapshot(),result.results(),result.executedAt(),timing);
        store.save(execution);return execution;
    }
}
