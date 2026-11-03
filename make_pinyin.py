from pathlib import Path
import re,unicodedata,collections,hashlib,json
r=Path(__file__).parent
source=r/'pinyin-source.txt';d=collections.defaultdict(list)
common='的一是不了人我在有他这为之大来以个中上们到说国和地也子时道出而要于就下得可你年生自会那后能对着事其里所去行过家十用发天如然作方成者多日都三小军二无同么经法当起与好看学进种将还分此心前面又定见只主没公从全工使情明性知最现意已长动新期关点正业外两高间由问很最重并物手应战向头文体政美相被利什等产或月王果加本比次打爱机掌游戏视频科技术数码评测开箱旅行音乐动画教程电影手机电脑播放收藏投币评伦评论世界生活日常萌猫狗车模模拟器双屏设置搜索登录'
for line in source.read_text().splitlines():
 m=re.match(r'U\+([0-9A-F]+):\s+([^#]+)',line)
 if not m:continue
 ch=chr(int(m[1],16))
 try:ch.encode('gb2312')
 except UnicodeError:continue
 for py in m[2].strip().split(','):
  py=''.join(c for c in unicodedata.normalize('NFD',py).replace('u\u0308','v') if unicodedata.category(c)!='Mn').replace('u:','v')
  if py.isascii() and py.isalpha() and ch not in d[py]:d[py].append(ch)
for key,chars in d.items():chars.sort(key=lambda c:(common.find(c) if c in common else 99999,ord(c)))
(r/'assets/pinyin.tsv').write_text('\n'.join(k+'\t'+''.join(v) for k,v in sorted(d.items()))+'\n')
(r/'pinyin-provenance.json').write_text(json.dumps({'source':'https://github.com/mozillazg/pinyin-data','source_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),'syllables':len(d),'characters':len(set(c for v in d.values() for c in v))},indent=2))
print(len(d),'syllables',sum(len(v) for v in d.values()),'readings')
