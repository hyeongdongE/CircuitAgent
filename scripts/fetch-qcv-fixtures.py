#!/usr/bin/env python3
"""체크섬이 고정된 공개 원본만 내려받는다. 회로 파싱과 XLSX 변환은 Java에서 수행한다.
재배포 조건이 확인되지 않은 원본은 Git에서 제외된 .local에만 저장한다.
"""
import hashlib
import json
import urllib.request
from pathlib import Path

manifest = json.loads(Path(__file__).with_name('qcv-fixtures.json').read_text())
output = Path(__file__).resolve().parent.parent / '.local/qcv-fixtures'
output.mkdir(parents=True, exist_ok=True)
for item in manifest['files']:
    target = output / item['local']
    # 이미 받은 파일도 해시를 확인해야 수정되거나 손상된 원본으로 테스트하지 않는다.
    if target.exists() and hashlib.sha256(target.read_bytes()).hexdigest() == item['sha256']:
        continue
    # 브랜치 이름 대신 고정 Commit을 사용하여 재현 시점에 원본이 바뀌지 않게 한다.
    url = 'https://raw.githubusercontent.com/esharpab/hardware-ESH10000540-R3/' + manifest['commit'] + '/' + item['path']
    data = urllib.request.urlopen(url, timeout=60).read()
    # 다운로드 실패나 변경된 내용을 실제 설계 자료로 간주하지 않고 즉시 중단한다.
    if len(data) != item['bytes'] or hashlib.sha256(data).hexdigest() != item['sha256']:
        raise SystemExit('Source checksum mismatch: ' + item['local'])
    target.write_bytes(data)
print('Verified six pinned local-only sources in ' + str(output))
