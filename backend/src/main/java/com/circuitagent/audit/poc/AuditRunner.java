package com.circuitagent.audit.poc;
import com.circuitagent.design.graph.CircuitGraph;
import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Component;
import java.security.*;
import java.time.Instant;
import java.util.*;
@Component
public class AuditRunner {
    private final StructuredRuleAdapter adapter;private final DeterministicRuleChecker checker;private final ObjectMapper mapper;
    public AuditRunner(StructuredRuleAdapter a,DeterministicRuleChecker c,ObjectMapper m){adapter=a;checker=c;mapper=m;}
    public AuditExecution run(CircuitGraph graph,List<AuditRule> input) {
        var times=new LinkedHashMap<String,Double>();long start=System.nanoTime();var rules=adapter.validate(input);times.put("ruleValidationMs",elapsed(start));
        start=System.nanoTime();for(var r:rules) {
            try {graph.getComponentSpecification(r.target().reference());if(r.target().pinNumber()!=null){var p=graph.getPinContext(r.target().reference(),r.target().pinNumber());if(p.net()!=null)graph.getConnectedPins(p.net().id());}}catch(NoSuchElementException ignored){/* checker returns UNKNOWN with evidence */}
        }times.put("circuitTraversalPreflightMs",elapsed(start));
        start=System.nanoTime();var results=rules.stream().map(r->checker.evaluate(graph,r)).toList();times.put("ruleEvaluationIncludingGraphLookupsMs",elapsed(start));
        var d=graph.designSnapshot();
        return new AuditExecution(UUID.randomUUID(),d.id(),d.version(),hash(graph),rules,results,Instant.now(),times);
    }
    public String hash(CircuitGraph graph) {
        try {
            // Sort incidence lists as well as maps so persistence query order cannot alter the hash.
            var d=graph.designSnapshot();var tree=mapper.valueToTree(d);
            var key=Comparator.comparing((com.circuitagent.design.model.PinKey p)->p.reference()).thenComparing(p->p.number());
            ((com.fasterxml.jackson.databind.node.ObjectNode)tree).set("pins",mapper.valueToTree(d.pins().stream().sorted(Comparator.comparing(com.circuitagent.design.model.ComponentPin::key,key)).toList()));
            ((com.fasterxml.jackson.databind.node.ObjectNode)tree).set("connections",mapper.valueToTree(d.connections().stream().sorted(Comparator.comparing(com.circuitagent.design.model.PinConnection::pin,key)).toList()));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(sort(tree))));
        }catch(java.io.IOException|NoSuchAlgorithmException e){throw new IllegalStateException("Snapshot hash failed",e);}
    }
    private JsonNode sort(JsonNode n){if(n.isObject()){var o=mapper.createObjectNode();var names=new ArrayList<String>();n.fieldNames().forEachRemaining(names::add);Collections.sort(names);names.forEach(k->o.set(k,sort(n.get(k))));return o;}if(n.isArray()){var a=mapper.createArrayNode();n.forEach(v->a.add(sort(v)));return a;}return n;}
    private static double elapsed(long s){return (System.nanoTime()-s)/1_000_000.0;}
}
