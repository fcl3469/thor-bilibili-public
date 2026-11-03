"""Fetch pinned jlibtorrent artifacts; Android gets only its arm64 library."""
import hashlib
import json
import shutil
import subprocess
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def artifact(build, name):
    lock = json.loads((ROOT / 'torrent-dependencies.lock.json').read_text())
    item = next(a for a in lock['artifacts'] if a['name'] == name)
    target = Path(build) / 'torrent-deps' / name
    target.parent.mkdir(parents=True, exist_ok=True)
    if not target.exists() or hashlib.sha256(target.read_bytes()).hexdigest() != item['sha256']:
        url = 'https://github.com/frostwire/frostwire-jlibtorrent/releases/download/release/' + lock['version'] + '/' + name
        temporary = target.with_suffix('.download')
        try:
            subprocess.run(['curl', '--fail', '-L', '--retry', '2', '--max-time', '120', '-sS', url, '-o', str(temporary)], check=True)
            if hashlib.sha256(temporary.read_bytes()).hexdigest() != item['sha256']:
                raise RuntimeError('Torrent dependency SHA256 mismatch: ' + name)
            temporary.replace(target)
        finally:
            if temporary.exists():
                temporary.unlink()
    return target


def prepare_torrent(build):
    classes = artifact(build, 'jlibtorrent-2.0.12.9.jar')
    native = artifact(build, 'jlibtorrent-android-arm64-2.0.12.9.jar')
    with zipfile.ZipFile(native) as archive:
        names = [n for n in archive.namelist() if n.startswith('lib/arm64-v8a/libjlibtorrent-') and n.endswith('.so')]
        if len(names) != 1:
            raise RuntimeError('Unexpected Android torrent library layout')
        output = Path(build) / 'torrent-deps/arm64-v8a' / Path(names[0]).name
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_bytes(archive.read(names[0]))
    return classes, [('lib/arm64-v8a/' + output.name, output)]


if __name__ == '__main__':
    jar, libraries = prepare_torrent(ROOT / 'build')
    print(jar)
    for name, path in libraries:
        print(name, path)
