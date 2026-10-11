package com.circuitagent.audit.poc;
import java.util.List;
/** Stage 01 can supply a different adapter once its contract is agreed. */
public interface RuleInputAdapter { List<AuditRule> validate(List<AuditRule> rules); }
