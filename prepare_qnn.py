"""Pinned QNN/ORT runtime packaging for Snapdragon 8 Gen 2 / 8 Elite."""
import hashlib,json,shutil,urllib.request,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parent
ARTIFACTS={
 'qnn-runtime-2.42.0.aar':('https://repo.maven.apache.org/maven2/com/qualcomm/qti/qnn-runtime/2.42.0/qnn-runtime-2.42.0.aar','ecb69bd6a7b1be4717ef10805199fb49ec6b60ca3f481d3068ed9605fba91d5b'),
 'onnxruntime-android-qnn-1.23.2.aar':('https://repo.maven.apache.org/maven2/com/microsoft/onnxruntime/onnxruntime-android-qnn/1.23.2/onnxruntime-android-qnn-1.23.2.aar','66f16d022a0d9532af73c473b002db53b3d3c46ad400bbf3e40a1e81a647b6ae'),
}
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def prepare_qnn(build):
 deps=build/'qnn-deps';deps.mkdir(parents=True,exist_ok=True)
 libraries=[];assets=build/'qnn-assets';model=assets/'rife-qnn'
 # Rebuild our generated asset directory so removed experiment profiles cannot
 # survive in a reused build directory or its checksum manifest.
 if model.exists():shutil.rmtree(model)
 model.mkdir(parents=True,exist_ok=True)
 for name,(url,digest) in ARTIFACTS.items():
  archive=deps/name
  if not archive.exists() or sha(archive)!=digest:
   cached=ROOT/'build/rife-opt'/name
   if cached.exists() and sha(cached)==digest:shutil.copyfile(cached,archive)
   else:
    temporary=archive.with_suffix('.download')
    with urllib.request.urlopen(url,timeout=60) as src,temporary.open('wb') as out:shutil.copyfileobj(src,out)
    if sha(temporary)!=digest:raise RuntimeError('QNN dependency checksum mismatch: '+name)
    temporary.replace(archive)
  target=deps/('ort' if name.startswith('onnxruntime') else 'qnn')
  with zipfile.ZipFile(archive) as z:
   for item in z.namelist():
    filename=Path(item).name
    selected=item.startswith('headers/') or filename in ('libonnxruntime.so','libQnnHtp.so','libQnnHtpPrepare.so','libQnnHtpV73Stub.so','libQnnHtpV73Skel.so','libQnnHtpV79Stub.so','libQnnHtpV79Skel.so','libQnnSystem.so','LICENSE.pdf','NOTICE.txt')
    if not selected or item.endswith('/'):continue
    if Path(item).is_absolute() or '..' in Path(item).parts:raise RuntimeError('Unsafe QNN archive entry')
    output=target/item;output.parent.mkdir(parents=True,exist_ok=True);data=z.read(item)
    if not output.exists() or sha(output)!=hashlib.sha256(data).hexdigest():output.write_bytes(data)
    if filename in ('libQnnHtpV73Skel.so','libQnnHtpV79Skel.so'):shutil.copyfile(output,model/filename)
    elif item.startswith('jni/'):libraries.append(('lib/arm64-v8a/'+filename,output))
    elif filename in ('LICENSE.pdf','NOTICE.txt'):
     license_dir=assets/'native-licenses';license_dir.mkdir(exist_ok=True);shutil.copyfile(output,license_dir/('qnn-'+filename))
 for profile in ('landscape','portrait','landscape-900','portrait-900','landscape-1080','portrait-1080','landscape-full-540','portrait-full-540','landscape-full-720','portrait-full-720'):
  dest=model/profile;dest.mkdir(exist_ok=True)
  for name in (('encode.onnx','block0.onnx','block1.onnx','block2.onnx','block3.onnx') if '-full-' in profile else ('encode.onnx','block0.onnx','block1.onnx')):
   shutil.copyfile(ROOT/'native/vendor/rife-qnn'/profile/name,dest/name)
 for name in ('rife_hybrid_prepare_vec.comp','rife_hybrid_flow.comp','rife_hybrid_prepare_full.comp','rife_hybrid_flow_full.comp','rife_hybrid_middle_prepare.comp','rife_hybrid_middle_tex.comp','rife_hybrid_refine_prepare.comp','rife_hybrid_refine_tex.comp'):shutil.copyfile(ROOT/'native'/name,model/name)
 hashes={str(p.relative_to(model)):sha(p) for p in sorted(model.rglob('*')) if p.is_file() and p.name!='sha256.properties'}
 (model/'sha256.properties').write_text(''.join(k+'='+v+'\n' for k,v in hashes.items()))
 (build/'qnn-inputs.json').write_text(json.dumps({'artifacts':ARTIFACTS,'assets':hashes},indent=2)+'\n')
 from prepare_nnapi import prepare_nnapi
 prepare_nnapi(assets)
 return deps/'ort',libraries
