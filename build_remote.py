"""Build pinned app-local tsnet/JNI and collect licenses for linked Go modules."""
import hashlib,os,shutil,subprocess
from pathlib import Path

ROOT=Path(__file__).resolve().parent
def prepare_remote(build):
    go=Path(os.environ.get('THOR_GO',str(Path.home()/'.local/share/thor-go/go/bin/go')))
    if not go.is_file():
        found=shutil.which('go')
        if not found:raise RuntimeError('Install Go 1.27.1+ and set THOR_GO for the embedded remote link')
        go=Path(found)
    sdk=Path(os.environ.get('ANDROID_SDK_ROOT',str(Path.home()/'Android/Sdk')))
    candidates=[Path(os.environ[k]) for k in ('ANDROID_NDK_HOME','ANDROID_NDK_ROOT') if os.environ.get(k)]
    candidates+=sorted((sdk/'ndk').glob('*'),reverse=True)+[Path.home()/'.local/share/thor-android/android-ndk-r27d']
    ndk=next((p for p in candidates if (p/'build/cmake/android.toolchain.cmake').is_file()),None)
    if ndk is None:raise RuntimeError('Android NDK required for the embedded remote link')
    source=ROOT/'native/remote-link';out=build/'remote-link';out.mkdir(parents=True,exist_ok=True)
    cc=ndk/'toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android30-clang'
    env=dict(os.environ,GOOS='android',GOARCH='arm64',CGO_ENABLED='1',CC=str(cc),GOMAXPROCS='2',PATH=str(go.parent)+os.pathsep+os.environ['PATH'])
    library=out/'libthor_link.so'
    subprocess.run([str(go),'build','-mod=readonly','-trimpath','-p','2','-buildmode=c-shared','-ldflags=-s -w -extldflags=-Wl,-z,max-page-size=16384','-o',str(library),'.'],cwd=source,env=env,check=True)
    licenses=out/'assets/remote-link-licenses';licenses.mkdir(parents=True,exist_ok=True)
    modules=subprocess.check_output([str(go),'list','-mod=readonly','-deps','-f','{{if .Module}}{{.Module.Dir}}{{end}}','.'],cwd=source,env=env,text=True)
    names=[]
    for module in sorted(set(line.strip() for line in modules.splitlines() if line.strip())):
        path=Path(module)
        if path==source:continue
        files=sorted(set(list(path.glob('LICENSE*'))+list(path.glob('COPYING*'))+list(path.glob('NOTICE*'))))
        key=hashlib.sha256(module.encode()).hexdigest()[:12]
        for file in files:
            if file.is_file():
                name=key+'-'+file.name;shutil.copyfile(file,licenses/name);names.append(path.name+' / '+name)
    goroot=Path(subprocess.check_output([str(go),'env','GOROOT'],text=True).strip())
    shutil.copyfile(goroot/'LICENSE',licenses/'Go-LICENSE')
    (licenses/'INDEX.txt').write_text('Tailscale tsnet and linked Go dependencies\n'+'\n'.join(names)+'\nGo standard library: Go-LICENSE\n')
    return [('lib/arm64-v8a/libthor_link.so',library)],out/'assets'

if __name__=='__main__':
    for name,file in prepare_remote(Path(os.environ.get('THOR_BUILD_DIR',str(ROOT/'build'))).resolve())[0]:print(name,file)
