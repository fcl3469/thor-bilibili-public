from pathlib import Path
import zipfile,hashlib,json,xml.etree.ElementTree as ET,shutil
ROOT=Path(__file__).parent

def prepare(build):
 dest=build/'libraries';dest.mkdir(exist_ok=True);jars=[];resources=[];packages=[]
 for item in json.loads((ROOT/'dependencies.lock.json').read_text()):
  source=ROOT/'deps'/item['file'];assert hashlib.sha256(source.read_bytes()).hexdigest()==item['sha256']
  if source.suffix=='.jar':jars.append(source);continue
  folder=dest/source.stem;folder.mkdir(exist_ok=True)
  with zipfile.ZipFile(source) as z:
   manifest=ET.fromstring(z.read('AndroidManifest.xml'));packages.append(manifest.attrib['package'])
   for name in z.namelist():
    if name=='classes.jar' or name.startswith('res/') and not name.endswith('/'):
     target=folder/name;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(z.read(name))
   jars.append(folder/'classes.jar')
   if (folder/'res').exists():resources.append(folder/'res')
 return jars,resources,packages
