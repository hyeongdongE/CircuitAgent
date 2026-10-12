-- Additive: V1/V2 and all prior pin relations are preserved.
ALTER TABLE component_pin ADD disposition VARCHAR(16) NOT NULL DEFAULT 'UNSPECIFIED',
  ADD source_metadata JSON;
UPDATE component_pin SET source_metadata = JSON_OBJECT() WHERE source_metadata IS NULL;
ALTER TABLE component_pin MODIFY source_metadata JSON NOT NULL;
