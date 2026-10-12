-- V1 is preserved; backfill existing designs before tightening integrity.
ALTER TABLE component_instance ADD assembly_status VARCHAR(16) NOT NULL DEFAULT 'UNSPECIFIED';
CREATE TABLE circuit_design_specification (
  design_id BINARY(16) NOT NULL,
  specification_id BINARY(16) NOT NULL,
  PRIMARY KEY (design_id, specification_id),
  FOREIGN KEY (design_id) REFERENCES circuit_design(id),
  FOREIGN KEY (specification_id) REFERENCES part_specification(id)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs;
INSERT INTO circuit_design_specification SELECT DISTINCT design_id, specification_id FROM component_instance;
ALTER TABLE component_instance ADD CONSTRAINT uq_component_design UNIQUE (id, design_id);
ALTER TABLE component_pin ADD design_id BINARY(16);
UPDATE component_pin p JOIN component_instance c ON p.component_id = c.id SET p.design_id = c.design_id;
ALTER TABLE component_pin MODIFY design_id BINARY(16) NOT NULL,
  ADD CONSTRAINT uq_pin_design UNIQUE (id, design_id),
  ADD CONSTRAINT fk_pin_component_design FOREIGN KEY (component_id, design_id) REFERENCES component_instance(id, design_id);
ALTER TABLE circuit_net ADD CONSTRAINT uq_net_design UNIQUE (id, design_id);
ALTER TABLE pin_connection
  ADD CONSTRAINT fk_connection_pin_design FOREIGN KEY (pin_id, design_id) REFERENCES component_pin(id, design_id),
  ADD CONSTRAINT fk_connection_net_design FOREIGN KEY (net_id, design_id) REFERENCES circuit_net(id, design_id);
