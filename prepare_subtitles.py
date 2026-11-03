#!/usr/bin/env python3
"""Pinned subtitle runtimes and a download manifest; weights stay outside the APK."""
import copy,hashlib,json,os,shutil,struct,subprocess,tarfile,urllib.request,zipfile
import xml.etree.ElementTree as E
from pathlib import Path
from tools.subtitle_eval.fetch_qnn_headers import prepare as qnn_headers

ROOT=Path(__file__).resolve().parent
A='{http://schemas.android.com/apk/res/android}'
def digest(path):
 value=hashlib.sha256()
 with Path(path).open('rb') as stream:
  for block in iter(lambda:stream.read(1048576),b''):value.update(block)
 return value.hexdigest()
def fetch(path,url,sha,candidates=()):
 path=Path(path)
 if path.is_file() and digest(path)==sha:return path
 path.parent.mkdir(parents=True,exist_ok=True)
 for candidate in candidates:
  if candidate.is_file() and digest(candidate)==sha:shutil.copyfile(candidate,path);return path
 temporary=path.with_suffix(path.suffix+'.partial')
 with urllib.request.urlopen(url,timeout=90) as source,temporary.open('wb') as output:shutil.copyfileobj(source,output)
 if digest(temporary)!=sha:raise RuntimeError('Subtitle input checksum mismatch: '+path.name)
 temporary.replace(path);return path
def untar(archive,destination):
 destination.mkdir(parents=True,exist_ok=True);base=destination.resolve()
 with tarfile.open(archive) as tar:
  # The pinned sherpa source includes two broken absolute symlinks in a Go
  # example unused by Android. Exclude those exact examples, then validate all
  # remaining members normally; never follow or materialize their links.
  unused_links={
   'sherpa-onnx-1.13.8/scripts/go/_internal/vad-spoken-language-identification/main.go',
   'sherpa-onnx-1.13.8/scripts/go/_internal/vad-spoken-language-identification/run.sh',
  }
  members=[item for item in tar.getmembers() if not (item.issym() and item.name in unused_links)]
  for item in members:
   target=(base/item.name).resolve()
   if target!=base and base not in target.parents:raise RuntimeError('Unsafe subtitle dependency archive')
   if item.islnk() or item.issym():
    link=(base/item.linkname if item.islnk() else target.parent/item.linkname).resolve()
    if link!=base and base not in link.parents:raise RuntimeError('Unsafe subtitle dependency link')
   elif not (item.isdir() or item.isfile()):raise RuntimeError('Unsupported dependency archive member')
  tar.extractall(destination,members=members)
def replace_dynamic_library(path,old,new):
 # Change DT_NEEDED/DT_SONAME in place, without relocating ELF segments/alignment.
 raw=bytearray(path.read_bytes());assert raw[:5]==b'\x7fELF\x02' and raw[5]==1
 offset=struct.unpack_from('<Q',raw,32)[0];entry,count=struct.unpack_from('<HH',raw,54);loads=[];dynamic=None
 for i in range(count):
  typ,flags,start,va,pa,filesz,memsz,align=struct.unpack_from('<IIQQQQQQ',raw,offset+i*entry)
  if typ==1:loads.append((va,start,filesz))
  if typ==2:dynamic=(start,filesz)
 assert dynamic
 strings=None;references=[]
 for pos in range(dynamic[0],sum(dynamic),16):
  kind,value=struct.unpack_from('<QQ',raw,pos)
  if kind==0:break
  if kind==5:strings=value
  if kind in (1,14):references.append(value)
 assert strings is not None
 base=next(start+strings-va for va,start,size in loads if va<=strings<va+size);changed=False
 for value in references:
  pos=base+value;end=raw.index(0,pos)
  if bytes(raw[pos:end])==old.encode():
   assert len(new)<=end-pos;raw[pos:end]=new.encode()+b'\0'*(end-pos-len(new));changed=True
 if not changed:raise RuntimeError('Expected ONNX runtime dependency absent')
 path.write_bytes(raw)

def prepare(build,ndk):
 lock=json.loads((ROOT/'subtitle-dependencies.lock.json').read_text());work=build/'subtitle-deps';work.mkdir(parents=True,exist_ok=True)
 caches=sorted((ROOT/'build').glob('subtitle-eval-*'),reverse=True)
 jars=[];resources=[];packages=[];native=[];manifests=[]
 for row in lock['translation_dependencies']:
  source=fetch(ROOT/'deps'/row['file'],row['url'],row['sha256'])
  if source.suffix=='.jar':jars.append(source);continue
  target=work/'libraries'/source.stem;target.mkdir(parents=True,exist_ok=True)
  with zipfile.ZipFile(source) as archive:
   manifests.append(E.fromstring(archive.read('AndroidManifest.xml').decode().replace('${applicationId}','local.thor.bilichannels')))
   packages.append(manifests[-1].get('package'))
   for name in archive.namelist():
    if name.endswith('/'):continue
    if name=='classes.jar' or name.startswith(('res/','assets/','jni/arm64-v8a/')):
     path=target/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(archive.read(name))
     if name.startswith('jni/arm64-v8a/'):native.append((name.replace('jni/','lib/',1),path))
  jars.append(target/'classes.jar')
  if (target/'res').is_dir():resources.append(target/'res')
 # Keep the older weight cache untouched, but never pass it to aapt2 again.
 assets=work/'download-assets';assets.mkdir(exist_ok=True);models=assets/'subtitles';models.mkdir(exist_ok=True)
 direct=lock['direct_translation'];asr=lock['asr']
 if asr['kind']!='reazon_cpu' or set(asr['files'])!=set(asr['file_sizes']) or set(asr['files'])!=set(asr['file_urls']):raise RuntimeError('Invalid downloadable ASR manifest')
 for name in asr['files']:
  if Path(name).name!=name or name in ('.','..') or asr['file_sizes'][name]<=0:raise RuntimeError('Invalid recognition model filename or length')
 model_manifest={'format':2,'delivery':'on_demand','asr':asr,'direct_translation':direct}
 (models/'manifest.json').write_text(json.dumps(model_manifest,ensure_ascii=False))
 source_archive=fetch(work/'sherpa-onnx-v1.13.8-source.tar.gz','https://codeload.github.com/k2-fsa/sherpa-onnx/tar.gz/refs/tags/v1.13.8','b0374cc56dbc186d442ae73d5de743bb092470b640c4c50ce7b029044c0c4fa8',[base/'sherpa-onnx-v1.13.8-source.tar.gz' for base in caches])
 ort_archive=fetch(work/'onnxruntime-android-1.28.2.zip','https://github.com/csukuangfj/onnxruntime-libs/releases/download/v1.28.2/onnxruntime-android-1.28.2.zip','01518867f78241138b6aa25925802e843a4fa9085af8d303d49e35bbb52aff4d',[base/'onnxruntime-android-1.28.2.zip' for base in caches])
 # Reuse the already configured lab build only after its pinned archives match.
 previous=next((base for base in caches if (base/'sherpa-android/CMakeCache.txt').is_file() and (base/source_archive.name).is_file() and (base/ort_archive.name).is_file() and digest(base/source_archive.name)==digest(source_archive) and digest(base/ort_archive.name)==digest(ort_archive)),None)
 if previous:
  source=previous/'source/sherpa-onnx-1.13.8';target=previous/'sherpa-android';ort=previous/'ort-1.28.2';sdk=previous/'qnn-sdk'
 else:
  source=work/'source/sherpa-onnx-1.13.8'
  if not source.is_dir():untar(source_archive,work/'source')
  target=work/'native';ort=work/'ort-1.28.2';ort.mkdir(exist_ok=True)
  with zipfile.ZipFile(ort_archive) as z:
   for name in z.namelist():
    if name.endswith('/'):continue
    relative=name.split('/',1)[1] if name.startswith('onnxruntime-android-1.28.2/') else name
    if not relative.startswith(('headers/','jni/arm64-v8a/')):continue
    path=ort/relative;path.parent.mkdir(parents=True,exist_ok=True);data=z.read(name)
    if not path.is_file() or path.read_bytes()!=data:path.write_bytes(data)
  sdk=qnn_headers(work/'qnn-sdk')
 sdk=qnn_headers(sdk)
 cmake=source/'CMakeLists.txt';body=cmake.read_text();extra='\nif(THOR_SUBTITLE_SOURCE)\n include("${THOR_SUBTITLE_SOURCE}/asr.cmake")\nendif()\n'
 if 'THOR_SUBTITLE_SOURCE' not in body:cmake.write_text(body+extra)
 env=dict(os.environ,QNN_SDK_ROOT=str(sdk),SHERPA_ONNXRUNTIME_INCLUDE_DIR=str(ort/'headers'),SHERPA_ONNXRUNTIME_LIB_DIR=str(ort/'jni/arm64-v8a'))
 command=['cmake','-S',str(source),'-B',str(target),'-DCMAKE_POLICY_VERSION_MINIMUM=3.5','-DCMAKE_BUILD_TYPE=Release','-DCMAKE_TOOLCHAIN_FILE='+str(ndk/'build/cmake/android.toolchain.cmake'),'-DANDROID_ABI=arm64-v8a','-DANDROID_PLATFORM=android-30','-DSHERPA_ONNX_ENABLE_QNN=ON','-DSHERPA_ONNX_ENABLE_JNI=OFF','-DSHERPA_ONNX_ENABLE_PORTAUDIO=OFF','-DSHERPA_ONNX_ENABLE_TTS=OFF','-DSHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=OFF','-DSHERPA_ONNX_ENABLE_WEBSOCKET=OFF','-DBUILD_SHARED_LIBS=ON','-DTHOR_SUBTITLE_SOURCE='+str(ROOT/'native/subtitles')]
 subprocess.run(command,env=env,check=True);subprocess.run(['cmake','--build',str(target),'--target','thor_subtitle_asr','--parallel',os.environ.get('THOR_NATIVE_JOBS','2')],env=env,check=True)
 output=work/'arm64-v8a';output.mkdir(exist_ok=True);strip=ndk/'toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip'
 for name,source in [('libthor_subtitle_asr.so',target/'lib/libthor_subtitle_asr.so'),('libthorort.so',ort/'jni/arm64-v8a/libonnxruntime.so')]:
  path=output/name;shutil.copyfile(source,path);subprocess.run([str(strip),'--strip-unneeded',str(path)],check=True);replace_dynamic_library(path,'libonnxruntime.so','libthorort.so');native.append(('lib/arm64-v8a/'+name,path))
 prepare_direct_translation(build,ndk,caches,direct,native)
 return jars,resources,packages,native,assets,manifests

def prepare_direct_translation(build,ndk,caches,direct,native):
 work=build/'subtitle-deps';row=direct['runtime'];archive=fetch(work/row['archive'],row['url'],row['sha256'],[base/row['archive'] for base in caches])
 previous=next((base for base in caches if (base/'llama-android/CMakeCache.txt').is_file() and (base/row['archive']).is_file() and digest(base/row['archive'])==row['sha256']),None)
 source=(previous/'source' if previous else work/'source')/row['directory'];target=previous/'llama-android' if previous else work/'translation-native'
 if not source.is_dir():untar(archive,source.parent)
 cmake=source/'CMakeLists.txt';body=cmake.read_text()
 if 'THOR_TRANSLATOR_SOURCE' not in body:cmake.write_text(body+'\nif(THOR_TRANSLATOR_SOURCE)\n include("${THOR_TRANSLATOR_SOURCE}/translation.cmake")\nendif()\n')
 command=['cmake','-S',str(source),'-B',str(target),'-DCMAKE_BUILD_TYPE=Release','-DCMAKE_TOOLCHAIN_FILE='+str(ndk/'build/cmake/android.toolchain.cmake'),'-DANDROID_ABI=arm64-v8a','-DANDROID_PLATFORM=android-30','-DANDROID_STL=c++_static','-DBUILD_SHARED_LIBS=OFF','-DGGML_OPENMP=OFF','-DGGML_LLAMAFILE=OFF','-DGGML_NATIVE=OFF','-DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod','-DLLAMA_BUILD_TESTS=OFF','-DLLAMA_BUILD_TOOLS=OFF','-DLLAMA_BUILD_EXAMPLES=OFF','-DTHOR_TRANSLATOR_SOURCE='+str(ROOT/'native/subtitles')]
 subprocess.run(command,check=True);subprocess.run(['cmake','--build',str(target),'--target','thor_subtitle_translate','--parallel',os.environ.get('THOR_NATIVE_JOBS','2')],check=True)
 path=work/'arm64-v8a/libthor_subtitle_translate.so';path.parent.mkdir(exist_ok=True);shutil.copyfile(target/'bin/libthor_subtitle_translate.so',path)
 subprocess.run([str(ndk/'toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip'),'--strip-unneeded',str(path)],check=True);native.append(('lib/arm64-v8a/'+path.name,path))

def merged_manifest(source,manifests,target):
 root=E.parse(source).getroot();application=root.find('application')
 def merge(parent,node):
  name=node.get(A+'name','');old=next((n for n in parent if n.tag==node.tag and n.get(A+'name','')==name),None)
  if old is None:parent.append(copy.deepcopy(node))
  else:
   for child in node:merge(old,child)
 for manifest in manifests:
  for node in manifest:
   if node.tag=='application':
    for child in node:
     if child.tag=='provider':
      child=copy.deepcopy(child);child.set(A+'process',':subtitles')
     merge(application,child)
   elif node.tag!='uses-sdk':merge(root,node)
 for node in root.iter():
  for key in list(node.attrib):
   if key.startswith('{http://schemas.android.com/tools}'):del node.attrib[key]
 E.register_namespace('android',A[1:-1]);E.ElementTree(root).write(target,encoding='utf-8',xml_declaration=True)
