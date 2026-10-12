"""Linux에서 생성한 lock 때문에 Windows 전용 의존성이 누락되는 회귀를 검증한다."""

from importlib.metadata import requires
from pathlib import Path

import pytest
from packaging.markers import default_environment
from packaging.requirements import Requirement
from packaging.utils import canonicalize_name


@pytest.mark.parametrize(
    "os_name,sys_platform,system",
    [("nt", "win32", "Windows"), ("posix", "linux", "Linux"), ("posix", "darwin", "Darwin")],
)
def test_platform_dependencies_are_pinned_and_hashed(os_name, sys_platform, system):
    environment = default_environment()
    environment.update(
        os_name=os_name,
        sys_platform=sys_platform,
        platform_system=system,
        python_version="3.12",
        python_full_version="3.12.14",
        extra="",
    )
    text = (Path(__file__).parents[1] / "requirements.lock").read_text()
    entries = {}
    for line in text.replace("\\\n", " ").splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        requirement = Requirement(line.split(" --hash=")[0].strip())
        assert any(s.operator == "==" for s in requirement.specifier)
        assert "--hash=sha256:" in line
        if requirement.marker is None or requirement.marker.evaluate(environment):
            entries[canonicalize_name(requirement.name)] = requirement
    # 실제 upstream 메타데이터의 조건을 사용해 Windows 전용 Colorama/TZData 누락을 찾는다.
    for package in ("build", "pytest", "pandas"):
        for raw in requires(package) or []:
            dependency = Requirement(raw)
            if dependency.marker is None or dependency.marker.evaluate(environment):
                assert canonicalize_name(dependency.name) in entries, f"Missing {dependency} for {system}"
