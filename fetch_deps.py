from pathlib import Path
import hashlib,json,subprocess,zipfile
ROOT=Path(__file__).parent
specs=[('androidx.media3',n,'1.11.1','aar') for n in ['media3-common','media3-container','media3-datasource','media3-decoder','media3-extractor','media3-database','media3-exoplayer']]+[
('androidx.annotation','annotation','1.6.0','jar'),('androidx.annotation','annotation-jvm','1.6.0','jar'),('androidx.annotation','annotation-experimental','1.3.1','aar'),('androidx.exifinterface','exifinterface','1.3.6','aar'),
('com.google.guava','guava','33.3.1-android','jar'),('com.google.guava','failureaccess','1.0.2','jar'),('com.google.guava','listenablefuture','9999.0-empty-to-avoid-conflict-with-guava','jar'),('com.google.code.findbugs','jsr305','3.0.2','jar'),('org.checkerframework','checker-qual','3.43.0','jar'),('com.google.errorprone','error_prone_annotations','2.28.0','jar'),('com.google.j2objc','j2objc-annotations','3.0.0','jar'),
('org.jetbrains.kotlin','kotlin-stdlib','1.8.0','jar'),('org.jetbrains.kotlin','kotlin-stdlib-common','1.8.0','jar'),('org.jetbrains','annotations','13.0','jar'),('com.google.zxing','core','3.5.3','jar')]
def fetch(url,path):
 if not path.exists():subprocess.run(['curl','-L','--fail','--retry','2','--max-time','60','-sS',url,'-o',str(path)],check=True)
 return hashlib.sha256(path.read_bytes()).hexdigest()
if __name__=='__main__':
 d=ROOT/'deps';d.mkdir(exist_ok=True);lock=[]
 for group,name,version,kind in specs:
  base=('https://dl.google.com/dl/android/maven2/' if group.startswith('androidx.') else 'https://repo.maven.apache.org/maven2/')+group.replace('.','/')+'/'+name+'/'+version+'/'+name+'-'+version
  f=d/(name+'-'+version+'.'+kind);sha=fetch(base+'.'+kind,f);fetch(base+'.pom',f.with_suffix('.pom'))
  with zipfile.ZipFile(f) as z:assert z.testzip() is None
  lock.append({'group':group,'name':name,'version':version,'file':f.name,'sha256':sha,'url':base+'.'+kind})
  print(f.name,flush=True)
 test=ROOT/'test-deps';test.mkdir(exist_ok=True)
 fetch('https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar',test/'json-20240303.jar')
 (ROOT/'dependencies.lock.json').write_text(json.dumps(lock,indent=2)+'\n')
