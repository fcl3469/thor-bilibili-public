#!/usr/bin/env python3
"""Reproducible compact full-pinyin index. Original dictionary sources stay in the source package."""
from pathlib import Path
import struct,re,json,hashlib
ROOT=Path(__file__).resolve().parent
inputs=[ROOT/'assets/rime-phrases.dict.yaml',ROOT/'dictionary-sources/rime-ice-base.dict.yaml',ROOT/'dictionary-sources/rime-ice-ext.dict.yaml',ROOT/'dictionary-sources/thor-extra.tsv']
words={}
for source in inputs:
 for line in source.read_text().splitlines():
  if not line or line.startswith('#'):continue
  p=line.split('\t')
  if len(p)<2:continue
  key=p[1].replace(' ','').replace('ü','v')
  if not re.fullmatch('[a-z]{1,48}',key):continue
  try:rank=float(p[2]) if len(p)>2 else 0
  except ValueError:rank=0
  group=words.setdefault(key,{})
  group[p[0]]=max(group.get(p[0],-1),rank)
blob=bytearray();offsets=[]
for key,values in sorted(words.items()):
 offsets.append(len(blob));line=key+'\t'+'\t'.join(sorted(values,key=lambda w:(-values[w],w)))+'\n';blob.extend(line.encode())
output=ROOT/'assets/phrases.bin'
with output.open('wb') as f:
 f.write(b'THP1');f.write(struct.pack('>II',len(offsets),len(blob)))
 for offset in offsets:f.write(struct.pack('>I',offset))
 f.write(blob)
report={'project':'iDvel/rime-ice','commit':'9e66b0729083b37d217312294f6d516c8d7234be','source':'https://github.com/iDvel/rime-ice','license':'GPL-3.0; upstream source attribution retained in each dictionary','generated_on':'2026-09-23','entries':sum(map(len,words.values())),'pinyin_keys':len(words),'bytes':output.stat().st_size,'files':{str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in inputs+[output,ROOT/'assets/rime-ice-LICENSE.txt']},'modifications':'Merged by exact pinyin/word, deduplicated, sorted by frequency; added thor-extra.tsv; compact sorted-key index. No online word harvesting.'}
(ROOT/'expanded-phrase-provenance.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
print(json.dumps({k:v for k,v in report.items() if k not in ('files',)},ensure_ascii=False))
