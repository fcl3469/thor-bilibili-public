#!/usr/bin/env python3
import hashlib, json, os, secrets, shutil, subprocess, zipfile, xml.etree.ElementTree as ET
from pathlib import Path
from prepare_libraries import prepare
from build_native import prepare_native
from build_remote import prepare_remote
from prepare_torrent import prepare_torrent
from prepare_subtitles import prepare as prepare_subtitles,merged_manifest
ROOT=Path(__file__).resolve().parent
SDK=Path(os.environ.get('ANDROID_SDK_ROOT',str(Path.home()/'Android/Sdk')))
JDK=Path(os.environ.get('JAVA_HOME','/usr/lib/jvm/java-17-openjdk-amd64'))
BT=SDK/'build-tools/36.0.0'; ANDROID=SDK/'platforms/android-35/android.jar'; BUILD=Path(os.environ.get('THOR_BUILD_DIR',ROOT/'build')).resolve()
ENV=dict(os.environ,JAVA_HOME=str(JDK),PATH=str(JDK/'bin')+os.pathsep+os.environ['PATH'],JAVA_TOOL_OPTIONS='-Xmx512m -XX:ActiveProcessorCount=2')
def run(*args):subprocess.run([str(a) for a in args],cwd=ROOT,env=ENV,check=True)
def main():
 native_libraries=prepare_native(BUILD)
 remote_libraries,remote_assets=prepare_remote(BUILD);native_libraries+=remote_libraries
 torrent_jar,torrent_libraries=prepare_torrent(BUILD);native_libraries+=torrent_libraries
 for folder in ['classes','gen','dex']:
  path=BUILD/folder
  if path.exists():shutil.rmtree(path)
  path.mkdir(parents=True)
 jars,resource_dirs,packages=prepare(BUILD)
 ndk=Path(os.environ.get('ANDROID_NDK_HOME',str(Path.home()/'.local/share/thor-android/android-ndk-r27d')))
 subtitle_jars,subtitle_resources,subtitle_packages,subtitle_libraries,subtitle_assets,subtitle_manifests=prepare_subtitles(BUILD,ndk)
 jars+=subtitle_jars;resource_dirs+=subtitle_resources;packages+=subtitle_packages;native_libraries+=subtitle_libraries
 manifest=BUILD/'AndroidManifest-merged.xml';merged_manifest(ROOT/'AndroidManifest.xml',subtitle_manifests,manifest)
 jars.append(torrent_jar)
 resource_zips=[]
 for i,folder in enumerate([ROOT/'res']+resource_dirs):
  result=BUILD/('resources-'+str(i)+'.zip')
  if result.exists():result.unlink()
  run(BT/'aapt2','compile','--dir',folder,'-o',result);resource_zips.append(result)
 run(BT/'aapt2','link','-o',BUILD/'resources.apk','-I',ANDROID,'--manifest',manifest,'--java',BUILD/'gen','-A','assets','-A',BUILD/'qnn-assets','-A',remote_assets,'-A',subtitle_assets,'-0','mp4','-0','zip','-0','gguf','--extra-packages',':'.join(sorted(set(packages))),*resource_zips)
 run(JDK/'bin/javac','-encoding','UTF-8','-source','8','-target','8','-classpath',os.pathsep.join(map(str,[ANDROID]+jars)),'-d',BUILD/'classes',*sorted((ROOT/'src').rglob('*.java')),*sorted((BUILD/'gen').rglob('*.java')))
 run(BT/'d8','--release','--min-api','30','--lib',ANDROID,'--output',BUILD/'dex',*sorted((BUILD/'classes').rglob('*.class')),*jars)
 shutil.copyfile(BUILD/'resources.apk',BUILD/'unsigned.apk')
 with zipfile.ZipFile(BUILD/'unsigned.apk','a',compression=zipfile.ZIP_DEFLATED) as z:
  for f in sorted((BUILD/'dex').glob('*.dex')):z.write(f,f.name)
  for name,path in native_libraries:z.write(path,name)
 run(BT/'zipalign','-f','4',BUILD/'unsigned.apk',BUILD/'aligned.apk')
 signing=ROOT/'.signing';signing.mkdir(mode=0o700,exist_ok=True);password=signing/'password';key=signing/'thor-bili.p12'
 if not password.exists():password.write_text(secrets.token_urlsafe(32));password.chmod(0o600)
 if not key.exists():run(JDK/'bin/keytool','-genkeypair','-keystore',key,'-storetype','PKCS12','-storepass:file',password,'-alias','thor-bili','-keyalg','RSA','-keysize','2048','-validity','3650','-dname','CN=Thor Bili Personal Preview')
 version=ET.parse(ROOT/'AndroidManifest.xml').getroot().attrib['{http://schemas.android.com/apk/res/android}versionName']
 result=BUILD/('Thor-Bili-'+version+'.apk')
 run(BT/'apksigner','sign','--ks',key,'--ks-pass','file:'+str(password),'--ks-key-alias','thor-bili','--out',result,BUILD/'aligned.apk')
 run(BT/'apksigner','verify','--verbose','--print-certs',result)
 run(BT/'zipalign','-c','4',result)
 inputs=[ROOT/'AndroidManifest.xml',ROOT/'dependencies.lock.json',ROOT/'build.py',ROOT/'build_remote.py',ROOT/'prepare_libraries.py',ROOT/'prepare_torrent.py',ROOT/'torrent-dependencies.lock.json',ROOT/'prepare_qnn.py',ROOT/'prepare_nnapi.py',ROOT/'prepare_dovi.py',ROOT/'dovi-dependencies.lock.json',ROOT/'build_native.py',ROOT/'native-dependencies.lock.json',ROOT/'prepare_subtitles.py',ROOT/'subtitle-dependencies.lock.json',ROOT/'tools/subtitle_eval/fetch_qnn_headers.py',ROOT/'tools/subtitle_eval/qnn-headers.lock.json']
 for folder in ['src','res','assets','native']:
  inputs += [f for f in (ROOT/folder).rglob('*') if f.is_file()]
 (BUILD/'source-inputs.json').write_text(json.dumps({str(f.relative_to(ROOT)):hashlib.sha256(f.read_bytes()).hexdigest() for f in inputs},indent=2))
 print('BUILT',result,'SHA256',hashlib.sha256(result.read_bytes()).hexdigest())
if __name__=='__main__':main()
