"""파일 형식에 독립적인 불변 회로 모델. 원문·누락값·연결 사실을 분리한다."""

from __future__ import annotations

from collections.abc import Mapping
from decimal import Decimal
from enum import StrEnum
from types import MappingProxyType
from typing import Any
from uuid import UUID, uuid4

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_serializer, model_validator
from pydantic.alias_generators import to_camel


def freeze(value: Any) -> Any:
    """Snapshot의 중첩 dict/list도 동결하여 조회 도중 외부 변경을 차단한다."""
    if isinstance(value, Mapping):
        return MappingProxyType({k: freeze(v) for k, v in value.items()})
    if isinstance(value, (list, tuple)):
        return tuple(freeze(v) for v in value)
    return value


def wire(value: Any) -> Any:
    """JSON 출력용 공통 표현. Decimal을 float로 바꿔 정밀도를 잃지 않는다."""
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
    return value


class Model(BaseModel):
    """모든 모델의 공통 기반. 필드 검증, 읽기 전용 처리, JSON 출력을 담당한다."""

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, frozen=True, extra="forbid")

    @field_validator("*", check_fields=False)
    @classmethod
    def identifier_not_blank(cls, value, info):
        """부품/핀/Net/설계 식별자는 빈 문자열을 거절한다."""
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
        """검증한 모델의 중첩 dict/list도 읽기 전용으로 바꿔 연결 관계의 외부 수정을 막는다."""
        for name in type(self).model_fields:
            object.__setattr__(self, name, freeze(getattr(self, name)))
        return self

    @model_serializer(mode="plain")
    def serialize(self):
        """모델을 camelCase JSON 필드와 Decimal 문자열로 직렬화한다."""
        return wire(self)


class AssemblyStatus(StrEnum):
    """FITTED=실장, DNP=미실장, UNSPECIFIED=실장 여부 미확정."""

    FITTED = "FITTED"
    DNP = "DNP"
    UNSPECIFIED = "UNSPECIFIED"


class PinDisposition(StrEnum):
    """연결과 별개로 핀 선언을 기록한다. TERMINATED를 오류나 Net 연결로 해석하지 않는다."""

    UNSPECIFIED = "UNSPECIFIED"
    UNCONNECTED = "UNCONNECTED"
    TERMINATED = "TERMINATED"


class AttributeValue(Model):
    """raw=원본 문자열, normalized=정규 숫자, unit=단위. 누락은 None이며 실제 0과 다르다."""

    raw: str | None = None
    normalized: Decimal | None = None
    unit: str | None = None

    @model_validator(mode="after")
    def numeric_unit(self):
        """정규 숫자는 유한 Decimal이며 단위가 있어야 한다. 원문만 있는 속성은 허용한다."""
        if self.normalized is not None and (not self.normalized.is_finite() or not self.unit):
            raise ValueError("Finite normalized attribute and unit required")
        return self


class PartSpecification(Model):
    """제조사/MPN/유형은 핵심 필드, attributes는 가변 속성 Map이다. 여러 인스턴스가 공유할 수 있다."""

    id: UUID = Field(default_factory=uuid4)
    manufacturer: str | None = None
    mpn: str | None = None
    type: str | None = None
    attributes: Mapping[str, AttributeValue] = Field(default_factory=dict)


class ComponentInstance(Model):
    """R1/U16처럼 회로에 쓰인 개별 부품. 사양 UUID, 원본 행, 실장 상태를 보존한다."""

    reference: str = Field(min_length=1, max_length=191)
    value: str | None = None
    part_specification_id: UUID
    raw_sources: Mapping[str, Mapping[str, str]] = Field(default_factory=dict)
    source_path: str | None = None
    assembly_status: AssemblyStatus = AssemblyStatus.UNSPECIFIED


class PinKey(Model):
    """핀의 고유 키는 Reference+핀 번호다. 핀 번호만으로 다른 부품의 핀을 합치지 않는다."""

    reference: str = Field(min_length=1, max_length=191)
    number: str = Field(min_length=1, max_length=128)


class ComponentPin(Model):
    """한 핀의 번호/선언 상태/원본 출처. QCV가 제공하지 않는 핀 기능 이름은 생성하지 않는다."""

    key: PinKey
    disposition: PinDisposition = PinDisposition.UNSPECIFIED
    source_metadata: Mapping[str, str] = Field(default_factory=dict)


class CircuitNet(Model):
    """동일한 전기 연결망. id는 조회 키이고 name은 원본 이름이며 전압을 뜻하지 않는다."""

    id: str = Field(min_length=1, max_length=191)
    name: str = Field(min_length=1)
    source_path: str | None = None


class PinConnection(Model):
    """하나의 PinKey가 어느 Net ID에 연결되는지 나타낸다. 부품 양단을 연결하는 edge가 아니다."""

    pin: PinKey
    net_id: str


class ImportIssue(Model):
    """파싱/중복/불일치 문제와 출처를 보존한다. 연결을 추정해서 오류를 숨기지 않는다."""

    severity: str
    code: str
    reference: str | None = None
    source: str | None = None
    message: str = Field(min_length=1)

    @model_validator(mode="after")
    def known_severity(self):
        """Issue 심각도는 WARNING 또는 ERROR만 허용한다."""
        if self.severity not in {"WARNING", "ERROR"}:
            raise ValueError("Invalid issue severity")
        return self


def issue(code, reference=None, source=None, message=None, error=False):
    """일관된 형식으로 경고/오류를 만든다. 잘못된 데이터를 성공으로 덮어쓰지 않는다."""
    return ImportIssue(
        severity="ERROR" if error else "WARNING",
        code=code,
        reference=reference,
        source=source,
        message=message or code,
    )


class BomResult(Model):
    """BOM 파싱 결과: 부품과 사양 및 Issue. 핀/Net 배선 정보는 포함하지 않는다."""

    components: Mapping[str, ComponentInstance]
    specifications: Mapping[UUID, PartSpecification]
    issues: tuple[ImportIssue, ...] = ()
    source_metadata: Mapping[str, str] = Field(default_factory=dict)


class NetlistResult(BomResult):
    """Netlist 공통 결과: 부품/핀/Net/연결과 원본 메타데이터."""

    pins: tuple[ComponentPin, ...]
    nets: Mapping[str, CircuitNet]
    connections: tuple[PinConnection, ...]
    source_metadata: Mapping[str, str]


class CircuitDesign(NetlistResult):
    """BOM 사양과 Netlist 연결이 결합된 회로. 생성 시 참조 무결성을 검증한다."""

    id: UUID = Field(default_factory=uuid4)
    name: str = Field(min_length=1, max_length=191)
    version: str = Field(min_length=1, max_length=64)

    @model_validator(mode="after")
    def relationships(self):
        """모든 사양/부품/핀/Net 참조와 중복 핀을 검증한다. 하나의 핀에 복수 Net 연결을 허용하지 않는다."""
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
