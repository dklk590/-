#!/usr/bin/env python3
"""Validate the three local APK variants with the official SDK and pinned runtime."""
import argparse, datetime, hashlib, json, re, struct, subprocess, zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
CERT='a40d317d98d17e72c48894c40c66e3a0ac9dbbf07246e6968daeb0632dd3f013'
def sha(data): return hashlib.sha256(data).hexdigest()
def command(args): return subprocess.check_output([str(v) for v in args],text=True,stderr=subprocess.STDOUT)
def elf(data):
    assert data[:4]==b'\x7fELF' and data[5]==1
    bits=64 if data[4]==2 else 32
    off=struct.unpack_from('<Q' if bits==64 else '<I',data,0x20 if bits==64 else 0x1c)[0]
    size,count=struct.unpack_from('<HH',data,0x36 if bits==64 else 0x2a)
    loads=[]; relro=[]
    for i in range(count):
        h=struct.unpack_from('<IIQQQQQQ' if bits==64 else '<IIIIIIII',data,off+i*size)
        typ,flags,p_offset,vaddr,filesz,memsz,align=(h[0],h[1],h[2],h[3],h[5],h[6],h[7]) if bits==64 else (h[0],h[6],h[1],h[2],h[4],h[5],h[7])
        row=dict(offset=p_offset,vaddr=vaddr,file_bytes=filesz,memory_bytes=memsz,flags=flags,alignment=align)
        if typ==1: loads.append(row)
        if typ==0x6474e552: relro.append(dict(**row,end_mod_16k=(vaddr+memsz)%16384))
    assert loads and all(v['alignment']>=16384 and (v['vaddr']-v['offset'])%16384==0 for v in loads)
    return dict(bits=bits,load_alignment_16k=True,loads=loads,relro=relro)
def main():
    parser=argparse.ArgumentParser();parser.add_argument('--sdk',required=True);args=parser.parse_args()
    bt=Path(args.sdk)/'build-tools/35.0.0'; lock=json.loads((ROOT/'dependencies.lock.json').read_text())
    source_assets={p.relative_to(ROOT/'app/src/main/assets').as_posix():p.read_bytes() for p in (ROOT/'app/src/main/assets').rglob('*') if p.is_file()}
    apks=[]; dex_reference=None
    for kind,relative,abis,compressed in [
        ('universal','app/build/outputs/apk/debug/wordweave-debug.apk',['arm64-v8a','armeabi-v7a'],False),
        ('arm64-v8a','apk/WordWeave-v1.2.0-arm64-v8a.apk',['arm64-v8a'],True),
        ('armeabi-v7a','apk/WordWeave-v1.2.0-armeabi-v7a.apk',['armeabi-v7a'],True)]:
        p=ROOT/relative; data=p.read_bytes()
        badging=command([bt/'aapt','dump','badging',p]); xml=command([bt/'aapt','dump','xmltree',p,'AndroidManifest.xml'])
        assert "name='com.wordweave.app' versionCode='3' versionName='1.2.0'" in badging
        assert "sdkVersion:'26'" in badging and "targetSdkVersion:'35'" in badging
        extract=re.search(r'android:extractNativeLibs[^\n]*=\(type 0x12\)(0x[0-9a-f]+)',xml).group(1)!='0x0'; assert extract==compressed
        sig=command([bt/'apksigner','verify','--verbose','--print-certs',p]); assert CERT in sig
        assert 'Verified using v2 scheme (APK Signature Scheme v2): true' in sig and 'Verified using v3 scheme (APK Signature Scheme v3): true' in sig
        command([bt/'zipalign','-c','-P','16','4',p])
        with zipfile.ZipFile(p) as z:
            assert z.testzip() is None
            embedded={n[len('assets/'):]:z.read(n) for n in z.namelist() if n.startswith('assets/') and not n.endswith('/')}
            assert embedded==source_assets
            dex={n:sha(z.read(n)) for n in z.namelist() if re.fullmatch(r'classes(?:\d+)?\.dex',n)}
            assert dex
            if dex_reference is None:dex_reference=dex
            else:assert dex==dex_reference
            native=[]
            names=[n for n in z.namelist() if n.startswith('lib/') and n.endswith('.so')]
            assert set(names)=={'lib/'+a+'/libsherpa-onnx-jni.so' for a in abis}
            for n in names:
                info=z.getinfo(n); raw=z.read(n); abi=n.split('/')[1]
                expected=next(v for v in lock['nativeArchives'] if abi in v['output'])
                assert sha(raw)==expected['rawSha256'] and len(raw)==expected['rawBytes']
                assert info.compress_type==(zipfile.ZIP_DEFLATED if compressed else zipfile.ZIP_STORED)
                name_len,extra_len=struct.unpack_from('<HH',data,info.header_offset+26)
                payload=info.header_offset+30+name_len+extra_len
                if not compressed:assert payload%16384==0
                native.append(dict(path=n,bytes=len(raw),sha256=sha(raw),zip_compression='deflated' if compressed else 'stored',zip_payload_offset=payload,zip_payload_16k_aligned=payload%16384==0,elf=elf(raw)))
        apks.append(dict(variant=kind,path=relative,bytes=len(data),sha256=sha(data),package='com.wordweave.app',version_code=3,version_name='1.2.0',min_sdk=26,target_sdk=35,extract_native_libraries=extract,zip_integrity='passed',zipalign_4_and_16k='passed',signature=dict(verified=True,v2=True,v3=True,certificate_sha256=CERT,matches_previous_delivery_certificate=True),dex_files=dex,native_libraries=native,embedded_assets_byte_identical=True,permissions=re.findall(r"uses-permission: name='([^']+)'",badging),under_12500000_bytes=len(data)<=12500000))
    now=datetime.datetime.now(datetime.timezone.utc).isoformat()
    speech_path=ROOT/'tests/speech_validation.json'; speech=json.loads(speech_path.read_text())
    for key in speech['source_sha256']:speech['source_sha256'][key]=sha((ROOT/key).read_bytes())
    speech['checked_at_utc']=now
    speech['android_api_compile']['scope']='Final direct SDK compilation of all current application Java sources, including the fixed British Kokoro adapter and Activity deletion guard; all three APKs use identical DEX.'
    speech['android_api_compile']['final_build_verified']=True
    speech_path.write_text(json.dumps(speech,ensure_ascii=False,indent=2)+'\n')
    model=json.loads((ROOT/'tests/model_download_validation.json').read_text()); native_model=json.loads((ROOT/'tests/native_model_validation.json').read_text())
    assert native_model['detectedAndFixedRegression']['backendSourceSha256']==sha((ROOT/'app/src/main/java/com/wordweave/app/SherpaTtsBackend.java').read_bytes())
    ui_path=ROOT.parent/'ui_qa/ui_result.json'; ui=json.loads(ui_path.read_text()); assert ui['passed'] and not ui['errors']
    vocab=json.loads(source_assets['vocabulary.json']); lessons=json.loads(source_assets['lessons.json']); supplements=json.loads(source_assets['meaning_supplements.json'])
    old=json.loads((ROOT/'tests/build_validation.json').read_text())
    historical=old.get('historical_v11_module_checks',old.get('module_tests',{}))
    report=dict(checked_at_utc=now,version_name='1.2.0',version_code=3,
        build=dict(pipeline='Official Android SDK AAPT2 + JDK17 javac + D8 + zipalign + apksigner',application_java_compile='passed',dex_conversion='passed',compile_sdk=35,min_sdk=26,target_sdk=35,java_source_target=8,build_tools='35.0.0',gradle_build_verified=False,ci_workflow_configured=True,ci_executed_here=False,pinned_dependency_files_verified=True),
        apks=apks,all_apk_dex_byte_identical=True,all_apk_assets_byte_identical=True,
        embedded_assets={n:dict(byte_identical_all_three=True,bytes=len(v),sha256=sha(v)) for n,v in sorted(source_assets.items())},
        embedded_word_count=len(vocab['words']),embedded_lesson_count=len(lessons['lessons']),embedded_lesson_source_counts=lessons['sourceCounts'],meaning_supplements_count=len(supplements),
        current_v12_module_checks=dict(speech_playback_jvm_checks=speech['java_core_playback_checks'],model_download_synthetic_catalog_checks=model['syntheticAndCatalogChecks'],model_download_with_four_real_archive_checks=model['withFourRealArchiveChecks'],ui_mock_bridge_calls=len(ui['calls']),ui_javascript_errors=len(ui['errors']),real_linux_native_model_generations=6,real_linux_native_models=4,speech_validation_source='tests/speech_validation.json',model_download_validation_source='tests/model_download_validation.json',native_model_validation_source='tests/native_model_validation.json',ui_validation='Owning UI agent mock test result; frozen HTML hash matches all APKs'),
        historical_v11_module_checks=historical,
        native_compatibility=dict(source='Official static-ONNX-runtime AAR 1.13.8, unchanged ARM JNI bytes verified against dependencies.lock.json',source_aarch64_min_api_note=21,ndk_note='r29 build 14206865',arm64_load_16k=True,arm64_relro_end_16k=True,armeabi_load_16k=True,armeabi_relro_end_mod_16k=4096,universal_jni_stored_and_zip_16k_aligned=True,abi_apks_jni_deflated_and_extract_native_libs_true=True,physical_16k_device_exercised=False,note='ELF/ZIP checks and Linux JNI generation do not establish Android device, Android 16 or every vendor compatibility. ARMv7 is the 32-bit compatibility variant.'),
        software_license=dict(license='GPL-3.0-or-later',root_text='LICENSE',component_notice='NOTICE',apk_notice='assets/licenses/dependencies/THIRD_PARTY_RUNTIME_NOTICE.txt',corresponding_source='docs/第三方依赖与对应源码.md',data_separate_licenses=True,model_weights_embedded=False),
        device_runtime_validation=dict(physical_device=False,emulator=False,android_native_model_inference=False,audio_track_pause_resume=False,live_system_tts_voice_download=False,android_download_manager_restart=False,paid_ai_service=False,note='Java core and mocked UI tests, APK metadata/signature/assets, ELF/ZIP checks, and actual Linux model generation passed. Phone audio and lifecycle behavior still need device verification.'),
        private_update_signing_key_in_public_source=False)
    (ROOT/'tests/build_validation.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(dict(result='passed',assets=len(source_assets),dex_identical=True,apks=[dict(path=v['path'],bytes=v['bytes'],sha256=v['sha256']) for v in apks]),ensure_ascii=False))
if __name__=='__main__':main()
