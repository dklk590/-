#!/usr/bin/env python3
"""Check bundled runtime artifacts against the reviewed dependency lock."""
from pathlib import Path
from prepare_native import prepare, digest, ROOT

def main():
    lock = prepare()
    expected = {entry['path'] for entry in lock['files']}
    actual = {path.relative_to(ROOT).as_posix() for path in (ROOT / 'app/libs').glob('*.jar')}
    if actual != expected:
        raise RuntimeError('The bundled JAR set differs from dependencies.lock.json.')
    for entry in lock['files']:
        path = ROOT / entry['path']
        if path.stat().st_size != entry['bytes'] or digest(path) != entry['sha256']:
            raise RuntimeError('Dependency checksum failed: ' + entry['path'])
    print('Bundled dependency verification passed: ' + str(len(expected)) + ' JARs and ' +
          str(len(lock['nativeArchives'])) + ' JNI libraries.')

if __name__ == '__main__':
    main()
