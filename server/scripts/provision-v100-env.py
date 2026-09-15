"""Provision a private V100 environment over SSH with temporary transport data.

The source env is a deployment input, never a checked-in configuration. Only
the account STT signing secret is reused; cloud/API/admin credentials are not
copied. No secret values are printed or passed in process arguments.
"""
import argparse
import json
import secrets
import subprocess
import tempfile
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-env", type=Path, required=True)
    parser.add_argument("--target", required=True)
    parser.add_argument("--destination", required=True)
    parser.add_argument("--model-root", required=True)
    parser.add_argument("--file-device", default="cuda:1")
    parser.add_argument("--bind", default="127.0.0.1")
    args = parser.parse_args()
    source = dict(line.split("=", 1) for line in args.source_env.read_text(encoding="utf-8-sig").splitlines()
                  if "=" in line and not line.lstrip().startswith("#"))
    account_secret = source.get("ACCOUNT_TOKEN_SECRET", "").strip().strip('"')
    if not account_secret:
        raise RuntimeError("Source account STT secret is missing")
    values = {"ACCOUNT_TOKEN_SECRET": account_secret, "V100_STT_TOKEN": secrets.token_urlsafe(32),
              "V100_MODEL_ROOT": args.model_root, "V100_PORT": "8889", "V100_DEVICE": "cuda:0",
              "V100_FILE_DEVICE": args.file_device, "V100_BIND": args.bind,
              "V100_STREAM_CHUNK_MS": "300", "V100_MAX_STREAMS": "2", "V100_CPU_THREADS": "4"}
    with tempfile.NamedTemporaryFile("w", suffix=".json", encoding="utf-8", delete=False) as handle:
        json.dump(values, handle)
        local_path = Path(handle.name)
    try:
        result = subprocess.run(["scp", "-o", "BatchMode=yes", "-o", "ConnectTimeout=15", str(local_path),
                                 f"{args.target}:{args.destination}"], capture_output=True, timeout=60)
        if result.returncode:
            raise RuntimeError("Remote environment provisioning failed (output withheld)")
    finally:
        local_path.unlink(missing_ok=True)
    print("Private V100 environment provisioned successfully")


if __name__ == "__main__":
    main()
