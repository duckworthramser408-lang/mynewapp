#!/usr/bin/env python3
"""Build using pinned Gradle; honor the cloud's HTTP CONNECT proxy without logging it."""
import os
from pathlib import Path
import subprocess
import sys
from urllib.parse import urlsplit
root=Path(__file__).resolve().parents[1]
gradle=os.environ.get('G23_GRADLE','/workspace/toolchains/gradle-8.9/bin/gradle')
args=[gradle,'--no-daemon']
proxy=os.environ.get('HTTPS_PROXY') or os.environ.get('https_proxy')
if proxy:
 p=urlsplit(proxy)
 if p.username or p.password: raise SystemExit('Authenticated proxy must be supplied by managed Java network settings; credentials are never written here.')
 for scheme in ('http','https'):
  args.extend([f'-D{scheme}.proxyHost={p.hostname}',f'-D{scheme}.proxyPort={p.port or 80}'])
 args.append('-Dhttp.nonProxyHosts=localhost|127.*')
args.extend(sys.argv[1:] or [':app:assembleDebug'])
raise SystemExit(subprocess.call(args,cwd=root/'android'))
