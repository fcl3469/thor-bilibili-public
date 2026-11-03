#!/usr/bin/env python3
import hashlib,io,json,os,subprocess,tempfile,zipfile,xml.etree.ElementTree as ET
from pathlib import Path
from build import ROOT,SDK,JDK,BT,ANDROID,ENV,BUILD,run

manifest=ET.parse(ROOT/'AndroidManifest.xml').getroot()
android_ns='{http://schemas.android.com/apk/res/android}'
version_name=manifest.attrib[android_ns+'versionName']
version_code=manifest.attrib[android_ns+'versionCode']
run('node',ROOT/'tests/xunlei-cloud.test.cjs')
apk=BUILD/('Thor-Bili-'+version_name+'.apk')
test_cp=os.pathsep.join(map(str,[ROOT/'test-deps/json-20240303.jar',ROOT/'deps/core-3.5.3.jar',ROOT/'deps/jsoup-1.23.2.jar']))
for name,sha in json.loads((BUILD/'source-inputs.json').read_text()).items():
    assert hashlib.sha256((ROOT/name).read_bytes()).hexdigest()==sha, 'Source changed after build: '+name
with tempfile.TemporaryDirectory(prefix='thor-bili-check-') as temp:
    run(JDK/'bin/javac','-encoding','UTF-8','-d',temp,'-classpath',test_cp,ROOT/'test-stubs/android/util/Log.java',*[ROOT/'src/local/thor/bilichannels'/f'{name}.java' for name in ['TorrentFiles','DonationAmount','XunleiLibrary','SubtitleTimeline','SubtitleSchedule','SubtitleAudioPrefetch','SubtitleWave','FrameCadence','RifeDevicePolicy','RifeFrameBudget','PgcModels','CommentTimestamps','PhonePreviewLayout','RelationModels','CreatorModels','ShortcutBindings','HttpDownload','ModelDownload','PlaybackGesture','FeedPager','SearchPager','SearchKeyboard','ControllerStick','BrowseState','DanmakuTrack','Comment','QualityOptions','Video','PreviewSeats','PreviewLayout','HandheldGrid','StreamSpec','SessionCookies','Wbi','ApiModels','PinyinInput']],*sorted((ROOT/'tests').glob('*.java')))
    run(JDK/'bin/java','-cp',temp+os.pathsep+test_cp,'local.thor.bilichannels.DonationTest')
    run(JDK/'bin/java','-cp',temp,'local.thor.bilichannels.RifeDevicePolicyTest')
    run(JDK/'bin/java','-cp',temp,'local.thor.bilichannels.FrameCadenceTest')
    run(JDK/'bin/java','-cp',temp+os.pathsep+test_cp,'local.thor.bilichannels.SubtitleTimelineTest')
    run(JDK/'bin/java','-cp',temp+os.pathsep+test_cp,'local.thor.bilichannels.SubtitlePrefetchTest')
    run(JDK/'bin/java','-cp',temp+os.pathsep+test_cp,'SearchGestureTest')
    run(JDK/'bin/java','-cp',temp+os.pathsep+test_cp,'FeedQualityTest')
    run(JDK/'bin/java','-cp',temp+os.pathsep+test_cp,'CreatorShortcutTest')
    run(JDK/'bin/java','-cp',temp+os.pathsep+test_cp,'RelationModelsTest')
    run(JDK/'bin/java','-cp',temp+os.pathsep+test_cp,'PhonePreviewLayoutTest')
    run(JDK/'bin/java','-cp',temp,'HttpDownloadTest')
    run(JDK/'bin/java','-cp',temp,'ModelDownloadTest')
    run(JDK/'bin/java','-cp',temp,'BrowseStateTest')
    run(JDK/'bin/java','-cp',temp,'ControllerStickTest')
    run(JDK/'bin/java','-cp',temp,'InputModelTest',ROOT/'assets/pinyin.tsv',ROOT/'assets/rime-phrases.dict.yaml',ROOT/'assets/phrases.bin')
    run(JDK/'bin/java','-cp',temp,'PreviewLayoutTest')
    run(JDK/'bin/java','-cp',temp+os.pathsep+test_cp,'HandheldGridTest')
    run(JDK/'bin/java','-cp',temp+os.pathsep+test_cp,'AccountAndMediaTest',ROOT/'assets/pinyin.tsv')
    run(JDK/'bin/java','-cp',temp+os.pathsep+test_cp,'QualityOptionsTest')
    run(JDK/'bin/java','-cp',temp+os.pathsep+test_cp,'CommentModelTest')
    run(JDK/'bin/java','-cp',temp,'CommentTimestampsTest')
    run(JDK/'bin/java','-cp',temp+os.pathsep+test_cp,'PgcModelsTest')
    run(JDK/'bin/java','-cp',temp+os.pathsep+test_cp,'DanmakuTrackTest')
    run(JDK/'bin/java','-cp',temp+os.pathsep+test_cp,'MobileDiscoveryTest')
    run(JDK/'bin/java','-cp',temp+os.pathsep+test_cp,'RelatedVideosTest')
    run(JDK/'bin/java','-cp',temp+os.pathsep+test_cp,'VideoDimensionsTest')
provenance=json.loads((ROOT/'expanded-phrase-provenance.json').read_text())
for name,sha in provenance['files'].items():
    assert hashlib.sha256((ROOT/name).read_bytes()).hexdigest()==sha, 'Dictionary provenance mismatch: '+name
assert 'DownloadManager' not in (ROOT/'src/local/thor/bilichannels/CacheStore.java').read_text()
with zipfile.ZipFile(apk) as archive:
    assert archive.testzip() is None
    assert 'classes.dex' in archive.namelist()
    assert any(b'Landroidx/media3/exoplayer/ExoPlayer;' in archive.read(name) for name in archive.namelist() if name.endswith('.dex'))
    assert 'assets/pinyin.tsv' in archive.namelist()
    assert not any('.signing' in p or p.endswith('.p12') for p in archive.namelist())
    assert not any(name.startswith('assets/cast-') for name in archive.namelist()), 'Unexpected catalog metadata asset'
    for name in archive.namelist():
        if name.endswith('.dex'):
            dex=archive.read(name)
            assert all(symbol not in dex for symbol in (b'CastCatalog',b'CastLookup',b'CastNames')), 'Unexpected catalog metadata implementation'
    subtitle_lock=json.loads((ROOT/'subtitle-dependencies.lock.json').read_text())
    subtitle_manifest=json.loads(archive.read('assets/subtitles/manifest.json'))
    assert subtitle_manifest['format']==2 and subtitle_manifest['delivery']=='on_demand'
    assert subtitle_manifest['asr']==subtitle_lock['asr']
    assert subtitle_manifest['direct_translation']==subtitle_lock['direct_translation']
    assert {name for name in archive.namelist() if name.startswith('assets/subtitles/')}=={'assets/subtitles/manifest.json'}
    assert not any(name.endswith('.gguf') or name.endswith('/asr.zip') for name in archive.namelist()), 'Subtitle model weights must not enter the small APK'
    assert apk.stat().st_size<200_000_000, 'On-demand model APK unexpectedly large'
    for name in ['ReazonSpeech-LICENSE.txt','Silero-VAD-LICENSE.txt','Hy-MT2-LICENSE.txt','NOTICE.txt']:
        assert archive.read('assets/subtitle-licenses/'+name)==(ROOT/'assets/subtitle-licenses'/name).read_bytes()
    for name in ['libthor_subtitle_asr.so','libthor_subtitle_translate.so','libthorort.so']:
        assert 'lib/arm64-v8a/'+name in archive.namelist()
    for i in range(4):
        name=f'assets/demo-{i}.mp4'
        assert archive.getinfo(name).compress_type==zipfile.ZIP_STORED
        assert archive.read(name)==(ROOT/name).read_bytes()
demo=[]
for i in range(4):
    f=ROOT/'assets'/f'demo-{i}.mp4'
    result=subprocess.run(['ffprobe','-v','error','-show_entries','format=duration:stream=codec_name,width,height','-of','json',str(f)],capture_output=True,text=True,check=True)
    media=json.loads(result.stdout)
    assert abs(float(media['format']['duration'])-10)<.05
    assert media['streams'][0]['codec_name']=='h264'
    assert (media['streams'][0]['width'],media['streams'][0]['height'])==((640,360) if i<2 else (360,640))
    demo.append(media)
signature=subprocess.run([str(BT/'apksigner'),'verify','--verbose','--print-certs',str(apk)],env=ENV,capture_output=True,text=True,check=True)
(BUILD/'signature.txt').write_text(signature.stdout)
package=subprocess.run([str(BT/'aapt2'),'dump','badging',str(apk)],env=ENV,capture_output=True,text=True,check=True)
(BUILD/'package.txt').write_text(package.stdout)
assert "name='local.thor.bilichannels'" in package.stdout
assert "minSdkVersion:'30'" in package.stdout
assert ("versionCode='"+version_code+"'") in package.stdout
assert ("versionName='"+version_name+"'") in package.stdout
assert '5bda8bd30f9873bd035c2ab63e534b22392a9b053e35d5873c2c58ea00718b4b' in signature.stdout
activity=manifest.find('application/activity')
assert '{http://schemas.android.com/apk/res/android}screenOrientation' not in activity.attrib
run(BT/'zipalign','-c','4',apk)
report={'apk':apk.name,'sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'bytes':apk.stat().st_size,'checks':['Java compilation and DEX','APK signature matches 0.1.0; versionCode '+version_code,'zip alignment and CRC','controller navigation model','3200 layout cases plus 44 phone single-column mixed layouts and persistent handheld seats: 4-6 rows, aspect combinations, focus priority, geometry, no overlap or stretch','4 bundled 10-second H264 clips: 2 landscape and 2 portrait, uncompressed assets','manifest allows phone rotation','account cookie origin/deletion, WBI signing, challenge detection, permitted quality/track selection, Chinese input and QR round-trip tests','Media3 1.11.1 included in DEX and pinned dependencies verified','19 structured comment cases; 15 danmaku movement, density, collision, pause and seek cases','10 mobile discovery checks: keyword grouping, page identity, challenge and mobile cookie scope','65 search pagination and gesture cases; 27 feed pagination and automatic quality cases; 7 streaming resume/cancel/length cases','899828 phrase entries: source and compiled index hashes verified','17 creator upload/dynamic parsing and key/analog trigger edge cases','App owned cache worker; no DownloadManager in CacheStore','no signing keys in APK'],'authenticated_account_actions_tested':False,'qr_confirmation_on_device_tested':False,'android_runtime_tested':False,'thor_hardware_tested':False,'multi_display_tested':False,'phone_rotation_and_touch_tested':False,'real_video_decode_on_android_tested':False,'notes':['This report covers host checks. Device evidence is merged only when its APK hash matches this build.'],'demo':demo}
report['checks'].append('On-demand subtitle manifest matches pinned sizes, sources and SHA256; no ASR/translation weights in APK; model licenses and runtimes present; APK below 200 MB')
for item in json.loads((ROOT/'dependencies.lock.json').read_text()):
    assert hashlib.sha256((ROOT/'deps'/item['file']).read_bytes()).hexdigest()==item['sha256']
features=BUILD/'features_api.json'
if features.exists():
    evidence=json.loads(features.read_text())
    if evidence.get('sha256')==report['sha256']:report['host_feature_api']=evidence
live=BUILD/'live_api.json'
if live.exists():
    evidence=json.loads(live.read_text())
    if evidence.get('sha256')==report['sha256']:report['host_live_api']=evidence
device=BUILD/'phone-validation.json'
if device.exists():
    proof=json.loads(device.read_text())
    if proof.get('sha256')==report['sha256'] and proof.get('passed'):
        report['phone_validation']=proof
        report['android_runtime_tested']=True
        report['real_video_decode_on_android_tested']=True
        report['phone_rotation_and_touch_tested']='landscape_preserves_player' in proof['checks']
        report['app_owned_download_tested']='video_and_audio_cache_complete' in proof['checks']
        report['notes']=proof.get('notes',[])+['Silent local media tests; Thor hardware, physical controller and account writes remain unverified.']
report['checks'].append('Handheld cards: 3780 vertical navigation cases, all-card reachability, screen bounds, full-frame fit and four-way stick mapping')
report['checks'].append('9 related-video parsing checks: deduplication, metadata, HTTPS covers, cap, unavailable entries, empty/error/challenge states')
thor=BUILD/'thor-validation.json'
if thor.exists():
    proof=json.loads(thor.read_text())
    if proof.get('sha256')==report['sha256'] and proof.get('passed'):
        report['thor_validation']=proof
        report['android_runtime_tested']=True
        report['thor_hardware_tested']=True
        report['multi_display_tested']=True
        report['real_video_decode_on_android_tested']=True
        report['physical_controller_tested']=False
        report['notes']=['Actual Thor dual-display UI and silent bundled decode checked; controller events injected into the app. Physical buttons, online full playback, long-duration use and account writes were not tested.']
(BUILD/'validation.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
print(json.dumps({k:v for k,v in report.items() if k!='demo'},ensure_ascii=False,indent=2))
