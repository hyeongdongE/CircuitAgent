package com.circuitagent.audit.poc;
import jakarta.persistence.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import com.circuitagent.design.persistence.CircuitDesignRepository;
import java.util.*;
@Repository
public class AuditStore {
    @PersistenceContext EntityManager em;
    private final CircuitDesignRepository designs;
    private final com.fasterxml.jackson.databind.ObjectMapper json;
    public AuditStore(CircuitDesignRepository designs,com.fasterxml.jackson.databind.ObjectMapper json){this.designs=designs;this.json=json;}
    @Transactional public void save(AuditExecution value) {
        if(!value.snapshotHash().matches("[0-9a-f]{64}") || value.ruleSnapshot().size()!=value.results().size())throw new IllegalArgumentException("Invalid execution snapshot");
        var e=new AuditExecutionEntity();e.id=value.id();e.designId=value.designId();e.designVersion=value.designVersion();e.snapshotHash=value.snapshotHash();e.executedAt=value.executedAt();
        e.metadata=encode(new AuditExecution(value.id(),value.designId(),value.designVersion(),value.snapshotHash(),value.ruleSnapshot(),List.of(),value.executedAt(),value.timingMs()));em.persist(e);
        for(int i=0;i<value.results().size();i++) {
            var v=value.results().get(i);var rule=value.ruleSnapshot().get(i);
            if(!rule.id().equals(v.ruleId()) || !rule.version().equals(v.ruleVersion()) || !rule.target().reference().equals(v.reference()))throw new IllegalArgumentException("Rule snapshot/result mismatch");
            var r=new AuditResultEntity();r.id=v.id();r.executionId=value.id();r.designId=value.designId();r.resultOrder=i;r.ruleId=v.ruleId();r.ruleVersion=v.ruleVersion();r.componentReference=v.reference();r.status=v.status();r.payload=encode(v);em.persist(r);
        }
        em.flush();
    }
    @Transactional(readOnly=true) public AuditExecution load(UUID designId,UUID id) {
        var row=em.find(AuditExecutionEntity.class,id);
        if(row==null || !row.designId.equals(designId))throw new NoSuchElementException("Audit not found");
        var meta=decode(row.metadata,AuditExecution.class);
        var results=em.createQuery("select r from AuditResultEntity r where r.executionId=:id and r.designId=:design",AuditResultEntity.class).setParameter("id",id).setParameter("design",designId).getResultList().stream().sorted(Comparator.comparingInt(r->r.resultOrder)).map(r->decode(r.payload,AuditResult.class)).toList();
        return new AuditExecution(meta.id(),meta.designId(),meta.designVersion(),meta.snapshotHash(),meta.ruleSnapshot(),results,meta.executedAt(),meta.timingMs());
    }
    // MySQL JSON numbers are binary64. A JSON string envelope preserves decimal/nanosecond evidence exactly.
    private Map<String,String> encode(Object value) {try{return Map.of("json",json.writeValueAsString(value));}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("Audit serialization failed",e);}}
    private <T>T decode(Map<String,String> value,Class<T> type){try{return json.readValue(value.get("json"),type);}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("Audit restoration failed",e);}}
    public record Summary(UUID id,UUID designId,String designVersion,java.time.Instant executedAt,Map<AuditStatus,Long> counts){}
    @Transactional(readOnly=true) public List<Summary> history(UUID designId) {
        if(!designs.existsById(designId))throw new NoSuchElementException("Design not found");
        return em.createQuery("select e from AuditExecutionEntity e where e.designId=:id order by e.executedAt desc",AuditExecutionEntity.class).setParameter("id",designId).setMaxResults(50).getResultList().stream().map(e->{
            var counts=new EnumMap<AuditStatus,Long>(AuditStatus.class);
            em.createQuery("select r.status, count(r) from AuditResultEntity r where r.executionId=:id group by r.status",Object[].class).setParameter("id",e.id).getResultList().forEach(x->counts.put((AuditStatus)x[0],(Long)x[1]));
            return new Summary(e.id,e.designId,e.designVersion,e.executedAt,Map.copyOf(counts));
        }).toList();
    }
}
