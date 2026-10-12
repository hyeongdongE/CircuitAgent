"""DB와 파일 형식에 독립적인 불변 회로 모델. 원문·누락값·연결 사실을 분리한다."""

from __future__ import annotations

from collections.abc import Mapping
from datetime import datetime
from decimal import Decimal
from enum import StrEnum
from types import MappingProxyType
from typing import Any
from uuid import UUID, uuid4

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_serializer, model_validator
from pydantic.alias_generators import to_camel


def freeze(value: Any) -> Any:
    """Snapshot의 중첩 dict/list도 동결하여 평가 도중 외부 변경을 차단한다."""
    if isinstance(value, Mapping):
        return MappingProxyType({k: freeze(v) for k, v in value.items()})
    if isinstance(value, (list, tuple)):
        return tuple(freeze(v) for v in value)
    return value


def wire(value: Any) -> Any:
    """REST/JSON 저장 공통 표현. Decimal을 float로 바꿔 정밀도를 잃지 않는다."""
    if isinstance(value, Model):
        return {
            field.alias or name: wire(getattr(value, name))
            for name, field in type(value).model_fields.items()
        }
    if isinstance(value, Mapping):
        return {str(k): wire(v) for k, v in value.items()}
    if isinstance(value, (tuple, list, set, frozenset)):
        return [wire(v) for v in value]
    if isinstance(value, (UUID, Decimal)):
        return str(value)
    if isinstance(value, datetime):
        return value.isoformat().replace("+00:00", "Z")
    return value


class Model(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, frozen=True, extra="forbid")

    @field_validator("*", check_fields=False)
    @classmethod
    def identifier_not_blank(cls, value, info):
        if (
            (
                info.field_name in {"reference", "number", "version", "id"}
                or info.field_name == "name"
                and cls.__name__ in {"CircuitNet", "CircuitDesign"}
            )
            and isinstance(value, str)
            and not value.strip()
        ):
            raise ValueError("Blank identifier is not allowed")
        return value

    @model_validator(mode="after")
    def immutable(self):
        for name in type(self).model_fields:
            object.__setattr__(self, name, freeze(getattr(self, name)))
        return self

    @model_serializer(mode="plain")
    def serialize(self):
        return wire(self)


class AssemblyStatus(StrEnum):
    FITTED = "FITTED"
    DNP = "DNP"
    UNSPECIFIED = "UNSPECIFIED"


class PinDisposition(StrEnum):
    UNSPECIFIED = "UNSPECIFIED"
    UNCONNECTED = "UNCONNECTED"
    TERMINATED = "TERMINATED"


class AttributeValue(Model):
    raw: str | None = None
    normalized: Decimal | None = None
    unit: str | None = None

    @model_validator(mode="after")
    def numeric_unit(self):
        if self.normalized is not None and (not self.normalized.is_finite() or not self.unit):
            raise ValueError("Finite normalized attribute and unit required")
        return self


class PartSpecification(Model):
    id: UUID = Field(default_factory=uuid4)
    manufacturer: str | None = None
    mpn: str | None = None
    type: str | None = None
    attributes: Mapping[str, AttributeValue] = Field(default_factory=dict)


class ComponentInstance(Model):
    reference: str = Field(min_length=1, max_length=191)
    value: str | None = None
    part_specification_id: UUID
    raw_sources: Mapping[str, Mapping[str, str]] = Field(default_factory=dict)
    source_path: str | None = None
    assembly_status: AssemblyStatus = AssemblyStatus.UNSPECIFIED


class PinKey(Model):
    reference: str = Field(min_length=1, max_length=191)
    number: str = Field(min_length=1, max_length=128)


class ComponentPin(Model):
    key: PinKey
    name: str | None = None
    electrical_type: str | None = None
    disposition: PinDisposition = PinDisposition.UNSPECIFIED
    source_metadata: Mapping[str, str] = Field(default_factory=dict)


class CircuitNet(Model):
    id: str = Field(min_length=1, max_length=191)
    name: str = Field(min_length=1)
    source_path: str | None = None


class PinConnection(Model):
    pin: PinKey
    net_id: str


class ImportIssue(Model):
    severity: str
    code: str
    reference: str | None = None
    source: str | None = None
    message: str = Field(min_length=1)

    @model_validator(mode="after")
    def known_severity(self):
        if self.severity not in {"WARNING", "ERROR"}:
            raise ValueError("Invalid issue severity")
        return self


def issue(code, reference=None, source=None, message=None, error=False):
    return ImportIssue(
        severity="ERROR" if error else "WARNING",
        code=code,
        reference=reference,
        source=source,
        message=message or code,
    )


class BomResult(Model):
    components: Mapping[str, ComponentInstance]
    specifications: Mapping[UUID, PartSpecification]
    issues: tuple[ImportIssue, ...] = ()


class NetlistResult(BomResult):
    pins: tuple[ComponentPin, ...]
    nets: Mapping[str, CircuitNet]
    connections: tuple[PinConnection, ...]
    source_metadata: Mapping[str, str]


class CircuitDesign(NetlistResult):
    id: UUID = Field(default_factory=uuid4)
    name: str = Field(min_length=1, max_length=191)
    version: str = Field(min_length=1, max_length=64)

    @model_validator(mode="after")
    def relationships(self):
        if any(k != v.id for k, v in self.specifications.items()):
            raise ValueError("Specification ID mismatch")
        if any(
            k != c.reference or c.part_specification_id not in self.specifications
            for k, c in self.components.items()
        ):
            raise ValueError("Invalid component specification/reference")
        keys = [p.key for p in self.pins]
        if len(set(keys)) != len(keys) or any(p.reference not in self.components for p in keys):
            raise ValueError("Unknown component or duplicate pin")
        if any(k != n.id for k, n in self.nets.items()):
            raise ValueError("Net ID mismatch")
        known = set(keys)
        connected = set()
        for c in self.connections:
            if c.pin not in known or c.net_id not in self.nets or c.pin in connected:
                raise ValueError("Dangling or ambiguous connection")
            connected.add(c.pin)
        return self
