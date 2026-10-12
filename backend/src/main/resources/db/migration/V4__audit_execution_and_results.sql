-- New PoC audit data only; V1-V3 source/schema/relations remain unchanged.
CREATE TABLE audit_execution (
 id BINARY(16) PRIMARY KEY, design_id BINARY(16) NOT NULL,
 design_version VARCHAR(64) NOT NULL, snapshot_hash VARCHAR(64) NOT NULL,
 executed_at DATETIME(6) NOT NULL, metadata JSON NOT NULL,
 UNIQUE KEY uq_audit_design (id, design_id),
 INDEX ix_audit_history (design_id, executed_at),
 CONSTRAINT fk_audit_design FOREIGN KEY (design_id) REFERENCES circuit_design(id)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs;
CREATE TABLE audit_result (
 id BINARY(16) PRIMARY KEY, execution_id BINARY(16) NOT NULL, design_id BINARY(16) NOT NULL,
 result_order INT NOT NULL, rule_id VARCHAR(128) NOT NULL, rule_version VARCHAR(64) NOT NULL,
 component_reference VARCHAR(191) NOT NULL, status VARCHAR(16) NOT NULL, payload JSON NOT NULL,
 UNIQUE KEY uq_audit_rule (execution_id, rule_id),
 INDEX ix_audit_results (execution_id, design_id, result_order),
 CONSTRAINT fk_audit_result_design FOREIGN KEY (execution_id, design_id) REFERENCES audit_execution(id, design_id),
 CONSTRAINT ck_audit_status CHECK (status IN ('PASS','FAIL','UNKNOWN','NOT_APPLICABLE','ERROR'))
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs;
