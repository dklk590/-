#!/usr/bin/env python3
"""Verify and restore bundled official JNI libraries; no network access is needed."""
import gzip
import hashlib
import json
import os
from pathlib import Path
import tempfile

ROOT = Path(__file__).resolve().parents[1]

def digest(path):
    result = hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            result.update(chunk)
    return result.hexdigest()

def prepare():
    lock = json.loads((ROOT / 'dependencies.lock.json').read_text(encoding='utf-8'))
    for entry in lock['nativeArchives']:
        archive = ROOT / entry['archive']
        output = ROOT / entry['output']
        if digest(archive) != entry['archiveSha256']:
            raise RuntimeError('Bundled native archive checksum failed: ' + entry['archive'])
        if output.is_file() and output.stat().st_size == entry['rawBytes'] and digest(output) == entry['rawSha256']:
            continue
        output.parent.mkdir(parents=True, exist_ok=True)
        temporary = None
        try:
            with tempfile.NamedTemporaryFile(dir=output.parent, prefix='.native-', delete=False) as target:
                temporary = Path(target.name)
                written = 0
                with gzip.open(archive, 'rb') as source:
                    for chunk in iter(lambda: source.read(1024 * 1024), b''):
                        written += len(chunk)
                        if written > entry['rawBytes']:
                            raise RuntimeError('Native archive expands beyond its declared size.')
                        target.write(chunk)
            if temporary.stat().st_size != entry['rawBytes'] or digest(temporary) != entry['rawSha256']:
                raise RuntimeError('Restored native library checksum failed: ' + entry['output'])
            os.replace(temporary, output)
        finally:
            if temporary is not None and temporary.exists():
                temporary.unlink()
    return lock

if __name__ == '__main__':
    manifest = prepare()
    print('Verified and prepared JNI libraries: ' + ', '.join(x['abi'] for x in manifest['nativeArchives']))
