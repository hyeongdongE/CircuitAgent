package com.circuitagent.audit.poc;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.*;
@RestController
public class AuditController {
    private final com.fasterxml.jackson.databind.ObjectMapper json;
    private final AuditService service;private final AuditStore store;private final PocRuleCatalog catalog;
    public AuditController(AuditService service,AuditStore store,PocRuleCatalog catalog,com.fasterxml.jackson.databind.ObjectMapper json){this.json=json;this.service=service;this.store=store;this.catalog=catalog;}
    public record CatalogView(String schemaVersion,String scope,Map<String,String> pinMapping,List<AuditRule> rules){}
    @GetMapping("/api/audit-rules") public CatalogView rules(){return new CatalogView("poc-rule-v1","Manual Sparrow FE R3 bindings; family/package candidate, unverified orderable MPN. Nominal facts, not full converter compliance.",catalog.pinMapping(),catalog.rules());}
    @PostMapping("/api/designs/{id}/audits") public ResponseEntity<AuditExecution> execute(@PathVariable UUID id,@RequestBody com.fasterxml.jackson.databind.JsonNode request) throws java.io.IOException {
        AuditService.Request input=json.readerFor(AuditService.Request.class).with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(request);
        var execution=service.execute(id,input);return ResponseEntity.created(URI.create("/api/designs/"+id+"/audits/"+execution.id())).body(execution);
    }
    @GetMapping("/api/designs/{id}/audits") public List<AuditStore.Summary> history(@PathVariable UUID id){return store.history(id);}
    @GetMapping("/api/designs/{id}/audits/{auditId}") public AuditExecution detail(@PathVariable UUID id,@PathVariable UUID auditId){return store.load(id,auditId);}
    @GetMapping("/api/designs/{id}/audits/{auditId}/results") public List<AuditResult> results(@PathVariable UUID id,@PathVariable UUID auditId){return store.load(id,auditId).results();}
}
