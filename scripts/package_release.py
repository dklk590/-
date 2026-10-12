"""Package source, documentation and a previously verified APK without SDK caches."""
from pathlib import Path
import argparse
import hashlib
import re
import shutil
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", type=Path, default=ROOT / "app/build/outputs/apk/debug/wordweave-debug.apk")
    parser.add_argument("--output", type=Path, default=ROOT.parent / "outputs/wordweave_android")
    args = parser.parse_args()
    if not args.apk.is_file():
        raise SystemExit("Build and verify an APK first.")
    args.output.mkdir(parents=True, exist_ok=True)
    version = re.search(r"versionName\s+['\"]([^'\"]+)['\"]", (ROOT / 'app/build.gradle').read_text()).group(1)
    apk = args.output / ("词链_WordWeave_v" + version + ".apk")
    archive = args.output / "词链_WordWeave_完整源码与说明.zip"
    shutil.copyfile(args.apk, apk)
    files = []
    for directory in ("app/src", "app/libs", "app/native", "docs", "LICENSES", "gradle", ".github", "scripts", "tests"):
        for path in (ROOT / directory).rglob("*"):
            if not path.is_file():
                continue
            rel = path.relative_to(ROOT)
            if any(part in ("__pycache__", "build", "android_compile", "node_modules", ".gradle") for part in rel.parts):
                continue
            if path.suffix in (".pyc", ".class"):
                continue
            if 'jniLibs' in rel.parts and path.suffix == '.so':
                continue
            if path.suffix in (".keystore", ".jks", ".p12", ".pfx", ".pem", ".key"):
                continue
            files.append(path)
    for name in ("README.md", "LICENSE", "NOTICE", "dependencies.lock.json", "build.gradle", "settings.gradle", "gradle.properties", "gradlew", "gradlew.bat", ".gitignore"):
        if (ROOT / name).is_file():
            files.append(ROOT / name)
    files.append(ROOT / "app/build.gradle")
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as out:
        for path in sorted(set(files)):
            out.write(path, str(Path("wordweave_android") / path.relative_to(ROOT)))
        out.write(apk, "wordweave_android/安装包/" + apk.name)
    with zipfile.ZipFile(archive) as out:
        assert out.testzip() is None
        assert "wordweave_android/app/src/main/assets/vocabulary.json" in out.namelist()
        assert "wordweave_android/app/libs/sherpa-onnx-android-1.13.8.jar" in out.namelist()
        assert "wordweave_android/app/native/libsherpa-onnx-jni-arm64-v8a.so.gz" in out.namelist()
        assert "wordweave_android/app/native/libsherpa-onnx-jni-armeabi-v7a.so.gz" in out.namelist()
        assert "wordweave_android/dependencies.lock.json" in out.namelist()
        assert "wordweave_android/LICENSE" in out.namelist()
        assert "wordweave_android/NOTICE" in out.namelist()
        assert not any('/jniLibs/' in name and name.endswith('.so') for name in out.namelist())
        assert not any(name.lower().endswith(('.keystore', '.jks', '.p12', '.pfx', '.pem', '.key', '.class')) for name in out.namelist())
        assert out.read("wordweave_android/安装包/" + apk.name) == apk.read_bytes()
    for path in (apk, archive):
        print(path)
        print("bytes=" + str(path.stat().st_size) + " sha256=" + hashlib.sha256(path.read_bytes()).hexdigest())


if __name__ == "__main__":
    main()
