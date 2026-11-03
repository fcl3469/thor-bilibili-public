"""Package the pinned RIFE convolution weights for native NNAPI (no SDK download).

Only layout changes and FP16 storage matching the QNN models are applied. Resolution
is chosen on device; weights are shared by every low-resolution profile.
"""
import hashlib
import math
import struct
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SOURCE_SHA = '8866a83e79676b57686d915f37663e306d1c7f30d1cbbca50d5809bfae42c212'


def read_layers():
    data = (ROOT / 'assets/rife-v4.12-lite/flownet.bin').read_bytes()
    if hashlib.sha256(data).hexdigest() != SOURCE_SHA:
        raise ValueError('Unexpected RIFE weights')
    cursor = 0
    def read(count, tagged):
        nonlocal cursor
        fmt = 'f'
        if tagged:
            tag, = struct.unpack_from('<I', data, cursor)
            cursor += 4
            if tag not in (0, 0x01306B47):
                raise ValueError('Unsupported ncnn weights')
            if tag: fmt = 'e'
        result = struct.unpack_from('<' + str(count) + fmt, data, cursor)
        cursor += (count * (2 if fmt == 'e' else 4) + 3) // 4 * 4
        if not all(map(math.isfinite, result)): raise ValueError('Nonfinite weight')
        return result
    layers = {}
    for line in (ROOT / 'assets/rife-v4.12-lite/flownet.param').read_text().splitlines()[2:]:
        words = line.split()
        kind, name = words[:2]
        ni, no = map(int, words[2:4])
        params = dict(word.split('=', 1) for word in words[4+ni+no:])
        def p(k, default): return int(params.get(str(k), default))
        if kind == 'MemoryData':
            layers[name] = read(p(0, 1)*p(1, 1)*p(2, 1), p(21, 1) == 0)
        elif kind in ('Convolution', 'Deconvolution'):
            co, kw, kh = p(0, 0), p(1, 0), p(11, p(1, 0))
            layers[name] = (kind, co, kw, kh, p(3, 1), p(4, 0),
                            read(p(6, 0), True), read(co, False) if p(5, 0) else (0.,)*co)
    if cursor != len(data): raise ValueError('Unconsumed weights')
    return layers


def encode_island(layers, kind):
    keys = []
    if kind == 'encode':
        keys = [('/encode/encode.%d/%s' % (i, 'ConvTranspose' if i == 6 else 'Conv'), i != 6, None)
                for i in (0, 2, 4, 6)]
    else:
        b = int(kind[-1])
        keys = [('/block%d/conv0/conv0.%d/conv0.%d.0/Conv' % (b, i, i), True, None) for i in (0, 1)]
        keys += [('/block%d/convblock/convblock.%d/conv/Conv' % (b, i), True,
                  layers['block%d.convblock.%d.beta' % (b, i)]) for i in range(8)]
        keys += [('/block%d/lastconv/lastconv.0/ConvTranspose' % b, False, None)]
    result = bytearray(b'RFNN0001' + struct.pack('<I', len(keys)))
    for key, activation, beta in keys:
        typ, co, kw, kh, stride, pad, weights, bias = layers[key]
        ci = len(weights)//(co*kw*kh)
        # ncnn OIHW -> NNAPI OHWI for both convolution and transpose convolution.
        packed = [weights[((o*ci+i)*kh+y)*kw+x]
                  for o in range(co) for y in range(kh) for x in range(kw) for i in range(ci)]
        result += struct.pack('<8I', typ == 'Deconvolution', co, ci, kh, kw, stride, pad,
                              (1 if activation else 0) | (2 if beta is not None else 0))
        result += struct.pack('<' + str(len(packed)) + 'e', *packed)
        result += struct.pack('<' + str(co) + 'e', *bias)
        if beta is not None: result += struct.pack('<' + str(co) + 'e', *beta)
    return bytes(result)


def prepare_nnapi(asset_root):
    target = asset_root/'rife-apu'
    target.mkdir(parents=True, exist_ok=True)
    layers = read_layers()
    for kind in ('encode', 'block0', 'block1'):
        (target/(kind+'.rfnn')).write_bytes(encode_island(layers, kind))
    for name in ('rife_hybrid_prepare_vec.comp', 'rife_hybrid_flow.comp'):
        (target/name).write_bytes((ROOT/'native'/name).read_bytes())
    hashes = {p.name: hashlib.sha256(p.read_bytes()).hexdigest()
              for p in sorted(target.iterdir()) if p.name != 'sha256.properties'}
    (target/'sha256.properties').write_text(''.join(k+'='+v+'\n' for k,v in hashes.items()))
    return hashes
