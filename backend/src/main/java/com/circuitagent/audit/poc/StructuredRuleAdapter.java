package com.circuitagent.audit.poc;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.net.URI;
import java.util.*;

@Component
public class StructuredRuleAdapter implements RuleInputAdapter {
    private static final Set<String> UNITS=Set.of("Ω","F","H","V","A","%");
    @Override public List<AuditRule> validate(List<AuditRule> rules) {
        if(rules==null || rules.isEmpty() || rules.size()>100) throw invalid();
        var ids=new HashSet<String>();
        for(var r:rules) {
            if(r==null || r.type()==null || r.target()==null || r.source()==null) throw invalid();
            text(r.id(),128);text(r.version(),64);text(r.condition(),2000);text(r.target().reference(),191);
            if(!ids.add(r.id())) throw invalid();
            var s=r.source();text(s.url(),1000);text(s.revision(),128);text(s.section(),256);text(s.summary(),2000);
            var uri=URI.create(s.url());if(!"https".equals(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || s.page()<1) throw invalid();
            if(s.sha256()==null || !s.sha256().matches("[0-9a-f]{64}")) throw invalid();
            if(!r.applicable())text(r.notApplicableReason(),2000);
            if(r.target().partName()!=null)text(r.target().partName(),191);
            if(r.type()==AuditRule.Type.PIN_CONNECTED || r.type()==AuditRule.Type.NET_MATCH || r.type()==AuditRule.Type.FB_DIVIDER)text(r.target().pinNumber(),128);
            if(r.type()==AuditRule.Type.NET_MATCH)text(r.expectedNet(),191);
            if(r.type()==AuditRule.Type.VALUE_RANGE || r.type()==AuditRule.Type.ATTRIBUTE_PRESENT) {
                text(r.attribute(),128);if(r.unit()==null || !UNITS.contains(r.unit()))throw invalid();
            }
            if(r.type()==AuditRule.Type.VALUE_RANGE)bounds(r.expected(),true);
            if(r.type()==AuditRule.Type.FB_DIVIDER) {
                if(!"V".equals(r.unit()) || r.divider()==null)throw invalid();
                var d=r.divider();text(d.upperReference(),191);text(d.lowerReference(),191);text(d.groundPin(),128);
                if(d.upperReference().equals(d.lowerReference()))throw invalid();
                positive(d.vref());positive(d.vrefMin());positive(d.vrefMax());
                if(d.vrefMin().compareTo(d.vref())>0 || d.vrefMax().compareTo(d.vref())<0)throw invalid();
                tolerance(d.resistorTolerancePercent(),false);if(r.expected()!=null)bounds(r.expected(),true);
            }
        }
        return List.copyOf(rules);
    }
    private static void bounds(AuditRule.Bounds b,boolean required) {
        if(b==null){if(required)throw invalid();return;}
        number(b.min());number(b.max());if(b.min().compareTo(b.max())>0)throw invalid();tolerance(b.tolerancePercent(),true);
    }
    private static void tolerance(BigDecimal v,boolean required) {if(v==null){if(required)throw invalid();return;}number(v);if(v.signum()<0 || v.compareTo(BigDecimal.valueOf(50))>0)throw invalid();}
    private static void positive(BigDecimal v) {number(v);if(v.signum()<=0)throw invalid();}
    private static void number(BigDecimal v) {if(v==null || v.precision()>32 || Math.abs((long)v.scale())>32)throw invalid();}
    private static void text(String s,int limit) {if(s==null || s.isBlank() || s.length()>limit || s.chars().anyMatch(Character::isISOControl))throw invalid();}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid poc-rule-v1 contract; explicit target, condition, unit, bounds/tolerance and source required");}
}
