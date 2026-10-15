#!/usr/bin/env python3
"""Pinned cloud toolchains in /workspace; no repository edits, no TLS bypass."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import urllib.request
import venv
import zipfile
ROOT=Path(__file__).resolve().parents[1]
BASE=Path(os.environ.get('G23_TOOLCHAINS','/workspace/toolchains'))

def run(args,**kw):
    subprocess.run([str(x) for x in args],check=True,**kw)

def main():
    BASE.mkdir(parents=True,exist_ok=True);downloads=BASE/'downloads';downloads.mkdir(exist_ok=True)
    pins=json.loads((ROOT/'tools/toolchains.json').read_text())
    for pin in pins:
        archive=downloads/pin['archive']
        if not archive.exists():
            temporary=archive.with_suffix(archive.suffix+'.part')
            with urllib.request.urlopen(pin['url'],timeout=120) as response, temporary.open('wb') as f:
                shutil.copyfileobj(response,f,1024*1024)
            temporary.replace(archive)
        if hashlib.file_digest(archive.open('rb'),'sha256').hexdigest()!=pin['sha256']:
            raise RuntimeError(f"{pin['name']}: checksum mismatch; remove the broken download and retry")
        target=BASE/pin['home']
        if not (target/pin['executable']).exists():
            if pin['name']=='jdk':
                with tarfile.open(archive) as f:f.extractall(BASE,filter='data')
            elif pin['name']=='gradle':
                with zipfile.ZipFile(archive) as f:f.extractall(BASE)
            else:
                with tempfile.TemporaryDirectory(dir=BASE) as temp:
                    with zipfile.ZipFile(archive) as f:f.extractall(temp)
                    target.parent.mkdir(parents=True,exist_ok=True)
                    shutil.move(str(Path(temp)/'cmdline-tools'),target)
        for executable in (target/'bin').iterdir():
            if executable.is_file():executable.chmod(executable.stat().st_mode|0o111)
    env=os.environ.copy()
    env.update(JAVA_HOME=str(BASE/pins[1]['home']),ANDROID_HOME=str(BASE/'android-sdk'),ANDROID_USER_HOME=str(BASE/'android-home'),GRADLE_USER_HOME=str(BASE/'gradle-home'))
    # Use the machine's managed CA store, including its HTTPS proxy CA.
    if Path('/etc/ssl/certs/java/cacerts').is_file():env['JAVA_TOOL_OPTIONS']=env.get('JAVA_TOOL_OPTIONS','')+' -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts'
    for name in ['android-home','gradle-home']:(BASE/name).mkdir(exist_ok=True)
    sdk=BASE/'android-sdk/cmdline-tools/latest/bin/sdkmanager'
    run([sdk,'--sdk_root='+str(BASE/'android-sdk'),'--licenses'],input=('y\n'*100).encode(),env=env,stdout=subprocess.DEVNULL)
    run([sdk,'--sdk_root='+str(BASE/'android-sdk'),'platforms;android-35','build-tools;35.0.0','ndk;27.0.12077973','cmake;3.22.1','platform-tools'],env=env)
    python=BASE/'g23-python/bin/python'
    if not python.exists():venv.create(BASE/'g23-python',with_pip=True)
    run([python,'-m','pip','install','--disable-pip-version-check','--cache-dir',BASE/'pip-cache','-r',ROOT/'tools/requirements.txt'])
    print('Toolchains verified. Run: source tools/env.sh')
if __name__=='__main__':main()
