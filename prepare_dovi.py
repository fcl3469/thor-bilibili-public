#!/usr/bin/env python3
"""Pinned, libdovi-enabled original-file decoder for local Dolby Vision builds."""
from pathlib import Path
import hashlib,json,shutil,urllib.request,zipfile
ROOT=Path(__file__).resolve().parent
def prepare_dovi(build):
    lock=json.loads((ROOT/'dovi-dependencies.lock.json').read_text())
    folder=build/'dovi-deps';folder.mkdir(parents=True,exist_ok=True)
    archive=folder/lock['archive']
    digest=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
    if not archive.exists() or digest(archive)!=lock['sha256']:
        cached=ROOT/'build/dovi-deps'/lock['archive']
        if cached.exists() and digest(cached)==lock['sha256']:shutil.copyfile(cached,archive)
        else:
            temporary=archive.with_suffix('.download')
            with urllib.request.urlopen(lock['url'],timeout=60) as src,temporary.open('wb') as out:shutil.copyfileobj(src,out)
            if digest(temporary)!=lock['sha256']:raise RuntimeError('Dolby decoder archive hash mismatch')
            temporary.replace(archive)
    libraries=[]
    with zipfile.ZipFile(archive) as z:
        for name,expected in lock['arm64'].items():
            data=z.read('jni/arm64-v8a/'+name)
            if hashlib.sha256(data).hexdigest()!=expected:raise RuntimeError('Dolby decoder library hash mismatch: '+name)
            target=folder/'arm64-v8a'/name;target.parent.mkdir(parents=True,exist_ok=True)
            if not target.exists() or digest(target)!=expected:target.write_bytes(data)
            libraries.append(('lib/arm64-v8a/'+name,target))
    return libraries
