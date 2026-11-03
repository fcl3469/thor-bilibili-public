#!/usr/bin/env python3
"""Check the packaged P5 decoder, pinned native dependencies and ELF ABI."""
import hashlib
import json
import struct
import xml.etree.ElementTree as ET
import zipfile
from build import ROOT, BUILD


def verify(apk):
    lock = json.loads((ROOT / 'dovi-dependencies.lock.json').read_text())
    hashes = {}
    with zipfile.ZipFile(apk) as archive:
        for name in ['libthor_dovi.so'] + list(lock['arm64']):
            binary = archive.read('lib/arm64-v8a/' + name)
            digest = hashlib.sha256(binary).hexdigest()
            if name in lock['arm64']:
                assert digest == lock['arm64'][name], name + ': dependency hash mismatch'
            assert binary[:6] == b'\x7fELF\x02\x01'
            assert struct.unpack_from('<H', binary, 18)[0] == 183, name + ': not AArch64'
            offset = struct.unpack_from('<Q', binary, 32)[0]
            size, count = struct.unpack_from('<HH', binary, 54)
            loads = [offset + i * size for i in range(count)
                     if struct.unpack_from('<I', binary, offset + i * size)[0] == 1]
            assert loads and all(struct.unpack_from('<Q', binary, p + 48)[0] >= 16384
                                 for p in loads), name + ': not 16K aligned'
            hashes[name] = digest
        bridge = archive.read('lib/arm64-v8a/libthor_dovi.so')
        for symbol in [b'DoviPlayer_nativeOpen', b'DoviPlayer_nativeClose', b'mpv_initialize']:
            assert symbol in bridge, symbol
        mpv = archive.read('lib/arm64-v8a/libmpv.so')
        assert b'dovi_parse_rpu' in mpv, 'Pinned decoder lacks libdovi RPU parser'
        for name in ['mpv-dovi.txt', 'mpv-gpl-3.0.txt', 'mpv-lgpl-2.1.txt']:
            assert archive.read('assets/native-licenses/' + name) == (
                ROOT / 'assets/native-licenses' / name).read_bytes()
    report = {
        'apk_sha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
        'native_sha256': hashes,
        'abi': 'arm64-v8a', 'elf_16k_alignment': True,
        'pinned_libdovi_bundle': True,
        'p5_path': 'software HEVC + RPU reshaping -> libplacebo GPU color mapping',
        'hdr_interpolation_path': '10-bit PQ input -> FP16 composition -> 10-bit PQ output',
        'motion_estimation': 'separate bounded SDR proxy; display frames retain HDR',
        'native_dolby_vision_display': False,
        'runtime_tested': False,
        'distribution': 'local development; GPL native bundle source obligations require review before binary publication',
    }
    (BUILD / 'dovi-validation.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    version = ET.parse(ROOT / 'AndroidManifest.xml').getroot().attrib[
        '{http://schemas.android.com/apk/res/android}versionName']
    verify(BUILD / ('Thor-Bili-' + version + '.apk'))
