#!/usr/bin/env python3
"""Build the pinned arm64 RIFE/Vulkan backend; never reuse an unchecked stale .so."""
import hashlib,json,os,shutil,subprocess,urllib.request,zipfile
from pathlib import Path
from prepare_qnn import prepare_qnn
from prepare_dovi import prepare_dovi
ROOT=Path(__file__).resolve().parent

def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def prepare_native(build):
 dovi_libraries=prepare_dovi(build)
 lock=json.loads((ROOT/'native-dependencies.lock.json').read_text())
 for name,digest in lock['files'].items():
  if sha(ROOT/name)!=digest: raise RuntimeError('Native dependency changed: '+name)
 sdk=Path(os.environ.get('ANDROID_SDK_ROOT',str(Path.home()/'Android/Sdk')))
 candidates=[Path(os.environ[v]) for v in ['ANDROID_NDK_HOME','ANDROID_NDK_ROOT'] if os.environ.get(v)]
 candidates+=sorted((sdk/'ndk').glob('*'),reverse=True)
 candidates+=[Path.home()/'.local/share/thor-android/android-ndk-r27d']
 ndk=next((p for p in candidates if (p/'build/cmake/android.toolchain.cmake').is_file()),None)
 if ndk is None: raise RuntimeError('Install Android NDK r27d or newer and set ANDROID_NDK_HOME; see native/README.md')
 deps=build/'native-deps';deps.mkdir(parents=True,exist_ok=True)
 item=lock['ncnn'];archive=deps/item['archive']
 if not archive.exists() or sha(archive)!=item['sha256']:
  # Keep the verified archive beside the source tree reusable by isolated build directories.
  cached=ROOT/'build/native-deps'/item['archive']
  if cached.exists() and sha(cached)==item['sha256']:shutil.copyfile(cached,archive)
  else:
   temporary=archive.with_suffix('.download')
   with urllib.request.urlopen(item['url'],timeout=60) as src,temporary.open('wb') as out:shutil.copyfileobj(src,out)
   if sha(temporary)!=item['sha256']:raise RuntimeError('ncnn archive SHA256 mismatch')
   temporary.replace(archive)
 # Extract immutable inputs and apply our reviewed ownership/synchronization patch.
 source=deps/item['directory']; source.mkdir(parents=True,exist_ok=True)
 patches=[ROOT/'native/ncnn-ahb-sync.patch',ROOT/'native/ncnn-ahb-rgba.patch',ROOT/'native/ncnn-hdr-output.patch']
 fingerprint=item['sha256']+''.join(sha(patch) for patch in patches)
 stamp=source/'.thor-source-sha'
 if not stamp.exists() or stamp.read_text()!=fingerprint:
  with zipfile.ZipFile(archive) as z:
   for member in z.infolist():
    p=Path(member.filename)
    if p.is_absolute() or '..' in p.parts:raise RuntimeError('Unsafe dependency archive')
    target=source/p
    if member.is_dir():target.mkdir(parents=True,exist_ok=True);continue
    data=z.read(member)
    if not target.exists() or target.read_bytes()!=data:
     target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(data)
  for patch in patches:subprocess.run(['git','apply','--unsafe-paths',str(patch)],cwd=source,check=True)
  stamp.write_text(fingerprint)
 target=build/'native/arm64-v8a';target.mkdir(parents=True,exist_ok=True)
 ort,qnn_libraries=prepare_qnn(build)
 command=['cmake','-S',str(ROOT/'native'),'-B',str(target),'-DCMAKE_BUILD_TYPE=Release',
  '-DCMAKE_TOOLCHAIN_FILE='+str(ndk/'build/cmake/android.toolchain.cmake'),'-DANDROID_ABI=arm64-v8a',
  '-DANDROID_PLATFORM=android-30','-DANDROID_STL=c++_static','-DTHOR_NCNN_SOURCE='+str(source),'-DTHOR_ORT_DIR='+str(ort),'-DTHOR_MPV_DIR='+str(build/'dovi-deps/arm64-v8a')]
 subprocess.run(command,check=True)
 subprocess.run(['cmake','--build',str(target),'--parallel',os.environ.get('THOR_NATIVE_JOBS','2')],check=True)
 library=target/'libthor_rife.so'
 if not library.is_file():raise RuntimeError('RIFE native library not produced')
 return [('lib/arm64-v8a/libthor_rife.so',library),('lib/arm64-v8a/libthor_dovi.so',target/'libthor_dovi.so'),('lib/arm64-v8a/libthor_subtitle_audio.so',target/'libthor_subtitle_audio.so')]+qnn_libraries+dovi_libraries

if __name__=='__main__':
 for name,path in prepare_native(Path(os.environ.get('THOR_BUILD_DIR',ROOT/'build')).resolve()):print(name,path,sha(path))
