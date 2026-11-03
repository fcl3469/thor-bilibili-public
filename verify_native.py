#!/usr/bin/env python3
"""Verify the actual packaged arm64 backend and its pinned model assets."""
import hashlib,json,struct,zipfile
from pathlib import Path
from build import ROOT,BUILD

def verify(apk):
 lock=json.loads((ROOT/'native-dependencies.lock.json').read_text())
 for name,digest in lock['files'].items():
  assert hashlib.sha256((ROOT/name).read_bytes()).hexdigest()==digest,name
 # Decoder surfaces stay GPU-only. Hybrid estimation tensors are explicitly
 # host-visible for QNN; QNN-internal transfers are not a zero-copy claim.
 java=(ROOT/'src/local/thor/bilichannels/RifeNative.java').read_text()
 native=(ROOT/'native/rife_jni.cpp').read_text()
 presenter=(ROOT/'native/vulkan_presenter.cpp').read_text()
 assert 'getHardwareBuffer()' in java and 'getPlanes()' not in java
 assert 'record_import_android_hardware_buffer' in native and 'ncnn::Mat ' not in native
 assert 'from_pixels' not in native and 'record_download' not in native
 assert 'vkMapMemory' not in presenter and 'vkCreateDevice(' not in presenter
 with zipfile.ZipFile(apk) as z:
  path='lib/arm64-v8a/libthor_rife.so';library=z.read(path)
  for symbol in [b'__kmpc_fork_call',b'__kmp_abort_process']:
   assert symbol not in library,'Unused crashing OpenMP runtime linked into RIFE: '+repr(symbol)
  assert library[:6]==b'\x7fELF\x02\x01' and struct.unpack_from('<H',library,18)[0]==183,'Expected AArch64 ELF'
  offset=struct.unpack_from('<Q',library,32)[0];entry_size,count=struct.unpack_from('<HH',library,54)
  loads=0
  for i in range(count):
   ph=offset+i*entry_size
   if struct.unpack_from('<I',library,ph)[0]==1:
    loads+=1;assert struct.unpack_from('<Q',library,ph+48)[0]>=16384,'Native load segment lacks 16K alignment'
  assert loads>0
  for name,digest in lock['files'].items():
   if name.startswith('assets/'):
    assert hashlib.sha256(z.read(name)).hexdigest()==digest,'Packaged dependency mismatch: '+name
  for symbol in [b'RifeNative_create',b'RifeNative_interpolate',b'RifeNative_present']:
   assert symbol in library,'Missing JNI entry: '+repr(symbol)
  import prepare_qnn
  for name,(url,digest) in prepare_qnn.ARTIFACTS.items():
   archive=BUILD/'qnn-deps'/name
   assert hashlib.sha256(archive.read_bytes()).hexdigest()==digest,'QNN archive mismatch'
   with zipfile.ZipFile(archive) as runtime:
    for item in runtime.namelist():
     filename=Path(item).name
     if filename in ('libonnxruntime.so','libQnnHtp.so','libQnnHtpPrepare.so','libQnnHtpV73Stub.so','libQnnHtpV79Stub.so','libQnnSystem.so'):
      assert z.read('lib/arm64-v8a/'+filename)==runtime.read(item),'Packaged QNN library mismatch'
     if filename in ('libQnnHtpV73Skel.so','libQnnHtpV79Skel.so'):
      assert z.read('assets/rife-qnn/'+filename)==runtime.read(item),'Packaged DSP library mismatch'
      assert 'lib/arm64-v8a/'+filename not in z.namelist(),'DSP ELF must remain an asset'
  import prepare_nnapi
  nnapi_layers=prepare_nnapi.read_layers()
  for kind in ('encode','block0','block1'):
   assert z.read('assets/rife-apu/'+kind+'.rfnn')==prepare_nnapi.encode_island(nnapi_layers,kind),'NNAPI weight conversion/package mismatch'
  for line in z.read('assets/rife-apu/sha256.properties').decode().splitlines():
   name,digest=line.split('=',1);assert hashlib.sha256(z.read('assets/rife-apu/'+name)).hexdigest()==digest,name
  for symbol in [b'ANeuralNetworksCompilation_createForDevices',b'ANeuralNetworksModel_getSupportedOperationsForDevices',b'RifeImageReader_acquire',b'RifeNative_submitImage']:
   assert symbol in library,'Missing APU/Android12 entry: '+repr(symbol)
  manifest=z.read('assets/rife-qnn/sha256.properties').decode()
  for line in manifest.splitlines():
   name,digest=line.split('=',1);assert hashlib.sha256(z.read('assets/rife-qnn/'+name)).hexdigest()==digest,name
  for name,digest in lock['files'].items():
   if name.startswith('native/vendor/rife-qnn/') and name.endswith('.onnx'):
    asset='assets/rife-qnn/'+name[len('native/vendor/rife-qnn/'):]
    assert hashlib.sha256(z.read(asset)).hexdigest()==digest,'Packaged profile differs from pinned model: '+name
  assert not any('/landscape-1440/' in n or '/portrait-1440/' in n for n in z.namelist()),'Rejected experimental profile packaged'
  assert 'lib/arm64-v8a/libQnnHtpV73Skel.so' not in z.namelist(),'DSP ELF must remain an asset'
 report={'apk':apk.name,'native_sha256':hashlib.sha256(library).hexdigest(),'abi':'arm64-v8a','ncnn':lock['ncnn']['version'],'model':lock['model']['name'],'elf_16k_alignment':True,'packaged_models_verified':True,'qnn_runtime_and_models_verified':True,'nnapi_packaged_weights_verified':True,'g90t_apu_device_tested':False,'android_runtime_tested':False,'frame_path':'AHardwareBuffer -> Vulkan; optional QNN HTP convolution -> Vulkan display-bounded composition/display','decoder_cpu_planes':False,'cpu_pixel_conversion':False,'host_visible_estimation_tensors':True,'qnn_internal_copies':'not measured','gpu_internal_copies':True,'path_check':'source inspection; device evidence is separate'}
 (BUILD/'native-validation.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))
if __name__=='__main__':
 import xml.etree.ElementTree as ET
 version=ET.parse(ROOT/'AndroidManifest.xml').getroot().attrib['{http://schemas.android.com/apk/res/android}versionName']
 verify(BUILD/('Thor-Bili-'+version+'.apk'))
