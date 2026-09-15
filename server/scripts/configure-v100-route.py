"""Switch only the local STT Nginx namespace, with validation and rollback."""
import argparse
import re
import shutil
import subprocess
import time
import urllib.request
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("--domain-config", type=Path, required=True)
parser.add_argument("--ip-config", type=Path, required=True)
parser.add_argument("--old-upstream", required=True)
parser.add_argument("--upstream", required=True)
parser.add_argument("--backup-dir", type=Path, required=True)
args = parser.parse_args()
response = urllib.request.urlopen(args.upstream + "/ready", timeout=5)
if response.status != 200:
    raise SystemExit("V100 upstream is not ready")
originals = {path: path.read_text() for path in (args.domain_config, args.ip_config)}
domain = originals[args.domain_config]
if domain.count(args.old_upstream) != 2:
    raise SystemExit("Unexpected old local STT upstream count")
domain = domain.replace(args.old_upstream, args.upstream)
ip = originals[args.ip_config]
if "location = /stt-local" in ip:
    raise SystemExit("IP local STT route already exists; review before changing")
blocks = re.findall(r"    location (?:= /stt-cloud(?:/ws/transcribe-stream)?|\^~ /stt-cloud/) \{.*?\n    \}", ip, re.S)
if len(blocks) != 3:
    raise SystemExit("Expected three existing cloud route blocks")
local = "\n\n".join(block.replace("/stt-cloud", "/stt-local").replace("http://127.0.0.1:8888", args.upstream).replace("proxy_connect_timeout 30s;", "proxy_connect_timeout 3s;") for block in blocks)
ip = ip.replace(blocks[0], local + "\n\n" + blocks[0], 1)
stamp = time.strftime("%Y%m%d-%H%M%S")
args.backup_dir.mkdir(parents=True, exist_ok=True)
for path in originals:
    shutil.copy2(path, args.backup_dir / (path.name + ".before-v100-" + stamp))
try:
    for path, text in ((args.domain_config, domain), (args.ip_config, ip)):
        temporary = path.with_name(path.name + ".v100-new")
        temporary.write_text(text)
        temporary.replace(path)
    subprocess.run(["nginx", "-t"], check=True)
    subprocess.run(["systemctl", "reload", "nginx"], check=True)
except Exception:
    for path, text in originals.items():
        path.write_text(text)
    subprocess.run(["nginx", "-t"], check=True)
    subprocess.run(["systemctl", "reload", "nginx"], check=True)
    raise
print("Local STT domain/IP routes now target the validated V100 relay")
