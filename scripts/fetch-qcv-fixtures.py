#!/usr/bin/env python3
"""Download only: no circuit parsing, conversion, backend, or unlicensed code reuse."""
from pathlib import Path
import hashlib, json, urllib.request
manifest = json.loads(Path(__file__).with_name('qcv-fixtures.json').read_text())
output = Path(__file__).resolve().parent.parent / '.local/qcv-fixtures'
output.mkdir(parents=True, exist_ok=True)
for item in manifest['files']:
    target = output / item['local']
    if target.exists() and hashlib.sha256(target.read_bytes()).hexdigest() == item['sha256']:
        continue
    url = 'https://raw.githubusercontent.com/esharpab/hardware-ESH10000540-R3/' + manifest['commit'] + '/' + item['path']
    data = urllib.request.urlopen(url, timeout=60).read()
    if len(data) != item['bytes'] or hashlib.sha256(data).hexdigest() != item['sha256']:
        raise SystemExit('Source checksum mismatch: ' + item['local'])
    target.write_bytes(data)
print('Verified six pinned local-only sources in ' + str(output))
