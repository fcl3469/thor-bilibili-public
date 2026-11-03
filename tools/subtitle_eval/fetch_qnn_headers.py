#!/usr/bin/env python3
"""Read just the pinned public SDK headers using checked ZIP HTTP ranges."""
import hashlib,io,json,urllib.request,zipfile
from pathlib import Path

def prepare(destination):
 lock=json.loads(Path(__file__).with_name('qnn-headers.lock.json').read_text());destination=Path(destination)
 def valid(row):
  path=destination/row['path'];return path.is_file() and hashlib.sha256(path.read_bytes()).hexdigest()==row['sha256']
 if all(valid(row) for row in lock['files']):return destination
 size=1403532983
 class Remote(io.RawIOBase):
  def __init__(self):self.position=0;self.blocks={}
  def seekable(self):return True
  def readable(self):return True
  def tell(self):return self.position
  def seek(self,offset,whence=0):
   target=offset if whence==0 else self.position+offset if whence==1 else size+offset
   if target<0:raise ValueError('Negative SDK archive position')
   self.position=target;return target
  def read(self,length=-1):
   end=min(size,self.position+length if length>=0 else size);result=[]
   while self.position<end:
    block=self.position//1048576
    if block not in self.blocks:
     start=block*1048576;stop=min(size,start+1048576)-1
     request=urllib.request.Request(lock['sdk_url'],headers={'Range':f'bytes={start}-{stop}'})
     with urllib.request.urlopen(request,timeout=60) as response:
      if response.status!=206 or response.headers.get('Content-Range')!=f'bytes {start}-{stop}/{size}':raise RuntimeError('SDK server did not honor the requested range')
      raw=response.read(stop-start+2)
     if len(raw)!=stop-start+1:raise RuntimeError('Incomplete SDK range')
     self.blocks[block]=raw
    raw=self.blocks[block];offset=self.position%1048576;count=min(end-self.position,len(raw)-offset);result.append(raw[offset:offset+count]);self.position+=count
   return b''.join(result)
 with zipfile.ZipFile(Remote()) as archive:
  names={name.split('/include/QNN/',1)[1]:name for name in archive.namelist() if '/include/QNN/' in name}
  for row in lock['files']:
   raw=archive.read(names[row['path'].split('include/QNN/',1)[1]])
   if hashlib.sha256(raw).hexdigest()!=row['sha256']:raise RuntimeError('SDK header checksum mismatch')
   target=destination/row['path'];target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(raw)
 return destination

if __name__=='__main__':
 import argparse
 parser=argparse.ArgumentParser();parser.add_argument('destination',type=Path);prepare(parser.parse_args().destination)
