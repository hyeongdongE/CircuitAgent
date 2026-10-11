package com.circuitagent.audit.poc;
import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Component;
import java.io.*;
import java.util.*;
@Component
public class PocRuleCatalog {
    private final List<AuditRule> rules;
    private final Map<String,String> pinMapping;
    public PocRuleCatalog(ObjectMapper mapper,StructuredRuleAdapter adapter) {
        try(var in=getClass().getResourceAsStream("/poc/tps54302-rules.json")) {
            var root=mapper.readTree(in);
            if(!"poc-rule-v1".equals(root.path("schemaVersion").asText()))throw new IllegalArgumentException("Unknown rule catalog schema");
            rules=adapter.validate(Arrays.asList(mapper.readerFor(AuditRule[].class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(root.get("rules"))));
            Map<String,String> mapping=new HashMap<>();root.get("pinMapping").properties().forEach(e->mapping.put(e.getKey(),e.getValue().asText()));pinMapping=Map.copyOf(mapping);
        } catch(IOException e){throw new IllegalStateException("Invalid bundled PoC rules",e);}
    }
    public List<AuditRule> rules(){return rules;}
    public Map<String,String> pinMapping(){return pinMapping;}
    public List<AuditRule> select(List<String> ids) {
        if(ids==null || ids.isEmpty() || ids.size()>100 || new HashSet<>(ids).size()!=ids.size())throw new IllegalArgumentException("Select unique rule IDs");
        return ids.stream().map(id->rules.stream().filter(r->r.id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("Unknown rule ID"))).toList();
    }
}
