CREATE TABLE circuit_design (
  id BINARY(16) PRIMARY KEY,
  name VARCHAR(191) NOT NULL,
  version VARCHAR(64) NOT NULL,
  imported_at DATETIME(6) NOT NULL,
  source_metadata JSON NOT NULL,
  CONSTRAINT uq_design_version UNIQUE (name, version)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs;
CREATE TABLE part_specification (
  id BINARY(16) PRIMARY KEY,
  manufacturer VARCHAR(512),
  mpn VARCHAR(512),
  component_type VARCHAR(128),
  attributes JSON NOT NULL,
  INDEX ix_spec_mpn (manufacturer(191), mpn(191))
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs;
CREATE TABLE component_instance (
  id BINARY(16) PRIMARY KEY,
  design_id BINARY(16) NOT NULL,
  specification_id BINARY(16) NOT NULL,
  component_reference VARCHAR(191) NOT NULL,
  component_value TEXT,
  source_path TEXT,
  raw_sources JSON NOT NULL,
  CONSTRAINT uq_component_reference UNIQUE (design_id, component_reference),
  FOREIGN KEY (design_id) REFERENCES circuit_design(id),
  FOREIGN KEY (specification_id) REFERENCES part_specification(id)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs;
CREATE TABLE component_pin (
  id BINARY(16) PRIMARY KEY,
  component_id BINARY(16) NOT NULL,
  number VARCHAR(128) NOT NULL,
  name TEXT,
  electrical_type VARCHAR(128),
  CONSTRAINT uq_component_pin UNIQUE (component_id, number),
  FOREIGN KEY (component_id) REFERENCES component_instance(id)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs;
CREATE TABLE circuit_net (
  id BINARY(16) PRIMARY KEY,
  design_id BINARY(16) NOT NULL,
  net_code VARCHAR(191) NOT NULL,
  name TEXT NOT NULL,
  source_path TEXT,
  CONSTRAINT uq_design_net UNIQUE (design_id, net_code),
  FOREIGN KEY (design_id) REFERENCES circuit_design(id)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs;
CREATE TABLE pin_connection (
  id BINARY(16) PRIMARY KEY,
  design_id BINARY(16) NOT NULL,
  pin_id BINARY(16) NOT NULL,
  net_id BINARY(16) NOT NULL,
  CONSTRAINT uq_connected_pin UNIQUE (pin_id),
  FOREIGN KEY (design_id) REFERENCES circuit_design(id),
  FOREIGN KEY (pin_id) REFERENCES component_pin(id),
  FOREIGN KEY (net_id) REFERENCES circuit_net(id),
  INDEX ix_connection_design_net (design_id, net_id)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs;
CREATE TABLE import_issue (
  id BINARY(16) PRIMARY KEY,
  design_id BINARY(16) NOT NULL,
  issue_order INT NOT NULL,
  severity VARCHAR(16) NOT NULL,
  code VARCHAR(64) NOT NULL,
  component_reference VARCHAR(191),
  source TEXT,
  message TEXT NOT NULL,
  FOREIGN KEY (design_id) REFERENCES circuit_design(id),
  INDEX ix_issue_design_order (design_id, issue_order)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs;
CREATE TABLE import_file (
  id BINARY(16) PRIMARY KEY,
  design_id BINARY(16) NOT NULL,
  role VARCHAR(16) NOT NULL,
  filename VARCHAR(512) NOT NULL,
  sha256 VARCHAR(64) NOT NULL,
  content LONGBLOB NOT NULL,
  CONSTRAINT uq_import_file_role UNIQUE (design_id, role),
  FOREIGN KEY (design_id) REFERENCES circuit_design(id)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs;
