#!/usr/bin/env python3
"""Build the Android app and its bundled, pinned runtime with the official SDK, without Gradle.

Usage: python3 scripts/build_sdk.py --sdk /absolute/path/to/Android/Sdk
Requires JDK17+, Android SDK Platform35 and Build Tools35.0.0.
Only the Python standard library is used; downloads are not performed.
"""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile


ROOT = Path(__file__).resolve().parents[1]


def run(args, env=None):
    print('+ ' + ' '.join(str(a) for a in args), flush=True)
    subprocess.run([str(a) for a in args], check=True, env=env)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', default=os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME'))
    parser.add_argument('--output', default=str(ROOT / 'app/build/outputs/apk/debug/wordweave-debug.apk'))
    parser.add_argument('--abi', choices=['all', 'arm64-v8a', 'armeabi-v7a'], default='all')
    parser.add_argument('--compress-native', action='store_true', help='Compress JNI entries for an ABI-specific downloadable APK; Android extracts them during install.')
    args = parser.parse_args()
    run([sys.executable, ROOT / 'scripts/prepare_native.py'])
    if not args.sdk:
        parser.error('Use --sdk or set ANDROID_HOME to an installed Android SDK.')
    sdk = Path(args.sdk).expanduser().resolve()
    tools = sdk / 'build-tools/35.0.0'
    android_jar = sdk / 'platforms/android-35/android.jar'
    if not android_jar.is_file():
        parser.error('Android SDK Platform35 is missing: ' + str(android_jar))
    suffix = '.exe' if os.name == 'nt' else ''
    shell_suffix = '.bat' if os.name == 'nt' else ''
    java_home = os.environ.get('JAVA_HOME')
    javac = Path(java_home) / ('bin/javac' + suffix) if java_home else shutil.which('javac')
    keytool = Path(java_home) / ('bin/keytool' + suffix) if java_home else shutil.which('keytool')
    if not javac or not keytool:
        parser.error('A JDK (javac and keytool) is required. Set JAVA_HOME to JDK17+.')
    aapt = tools / ('aapt2' + suffix)
    d8 = tools / ('d8' + shell_suffix)
    zipalign = tools / ('zipalign' + suffix)
    apksigner = tools / ('apksigner' + shell_suffix)
    for tool in [aapt, d8, zipalign, apksigner]:
        if not tool.is_file():
            parser.error('Build Tools35.0.0 is missing: ' + str(tool))
    source_root = ROOT / 'app/src/main'
    build = ROOT / 'app/build/direct'
    if build.exists():
        shutil.rmtree(build)
    for folder in ['generated', 'classes', 'dex']:
        (build / folder).mkdir(parents=True)
    resources_zip = build / 'resources.zip'
    direct_manifest = build / 'AndroidManifest.xml'
    ET.register_namespace('android', 'http://schemas.android.com/apk/res/android')
    manifest = ET.parse(source_root / 'AndroidManifest.xml')
    manifest.getroot().set('package', 'com.wordweave.app')
    manifest.getroot().find('application').set('{http://schemas.android.com/apk/res/android}extractNativeLibs', 'true' if args.compress_native else 'false')
    manifest.write(direct_manifest, encoding='utf-8', xml_declaration=True)
    unsigned = build / 'unsigned.apk'
    aligned = build / 'aligned.apk'
    output = Path(args.output).resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    run([aapt, 'compile', '--dir', source_root / 'res', '-o', resources_zip])
    run([aapt, 'link', '-I', android_jar, '--manifest', direct_manifest,
         '--min-sdk-version', '26', '--target-sdk-version', '35', '--version-code', '3',
         '--version-name', '1.2.0', '--auto-add-overlay', '-R', resources_zip,
         '-A', source_root / 'assets', '--java', build / 'generated', '-o', unsigned])
    sources = sorted((source_root / 'java').rglob('*.java')) + sorted((build / 'generated').rglob('*.java'))
    if not sources:
        parser.error('No Java sources were found.')
    sources_file = build / 'sources.txt'
    sources_file.write_text('\n'.join('"' + str(path).replace('\\', '/') + '"' for path in sources), encoding='utf-8')
    bootclasspath = str(android_jar) + os.pathsep + str(tools / 'core-lambda-stubs.jar')
    libraries = sorted((ROOT / 'app/libs').glob('*.jar'))
    classpath = os.pathsep.join(str(path) for path in libraries)
    run([javac, '-source', '8', '-target', '8', '-encoding', 'UTF-8', '-bootclasspath', bootclasspath,
         '-classpath', classpath,
         '-d', build / 'classes', '@' + str(sources_file)])
    class_files = sorted((build / 'classes').rglob('*.class'))
    run([d8, '--min-api', '26', '--lib', android_jar, '--output', build / 'dex'] + class_files + libraries)
    with zipfile.ZipFile(unsigned, 'a', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for path in sorted((build / 'dex').glob('*.dex')):
            archive.write(path, path.name)
        native_root = source_root / 'jniLibs'
        for path in sorted(native_root.rglob('*.so')):
            relative = path.relative_to(native_root)
            if relative.parts[0] not in ('arm64-v8a', 'armeabi-v7a'):
                continue
            if args.abi != 'all' and relative.parts[0] != args.abi:
                continue
            archive.write(path, 'lib/' + relative.as_posix(), compress_type=zipfile.ZIP_DEFLATED if args.compress_native else zipfile.ZIP_STORED)
    run([zipalign, '-f', '-P', '16', '4', unsigned, aligned])
    keystore = ROOT / 'signing/debug.keystore'
    if not keystore.is_file():
        keystore.parent.mkdir(parents=True, exist_ok=True)
        run([keytool, '-genkeypair', '-keystore', keystore, '-storepass', 'android', '-keypass', 'android',
             '-alias', 'androiddebugkey', '-dname', 'CN=Android Debug,O=WordWeave,C=CN',
             '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000', '-noprompt'])
    run([apksigner, 'sign', '--ks', keystore, '--ks-key-alias', 'androiddebugkey',
         '--ks-pass', 'pass:android', '--key-pass', 'pass:android', '--out', output, aligned])
    run([apksigner, 'verify', '--verbose', '--print-certs', output])
    run([zipalign, '-c', '-P', '16', '4', output])
    print('APK: ' + str(output))
    print('SHA256: ' + hashlib.sha256(output.read_bytes()).hexdigest())


if __name__ == '__main__':
    try:
        main()
    except subprocess.CalledProcessError as error:
        sys.exit(error.returncode)
