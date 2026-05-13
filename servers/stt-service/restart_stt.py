#!/usr/bin/env python3
import argparse
import os
import subprocess
import sys
import time
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--pid", type=int, required=True)
    parser.add_argument("--engine", required=True)
    parser.add_argument("--model", required=True)
    parser.add_argument("--port", type=int, default=8888)
    parser.add_argument("--model-root", required=True)
    parser.add_argument("--python", required=True)
    parser.add_argument("--service-dir", required=True)
    parser.add_argument("--stdout-log", required=True)
    parser.add_argument("--stderr-log", required=True)
    args = parser.parse_args()

    service_dir = Path(args.service_dir).resolve()
    server_script = service_dir / "stt_server.py"

    time.sleep(1.0)
    if os.name == "nt":
        subprocess.run(
            ["taskkill", "/PID", str(args.pid), "/F"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=False,
        )
    else:
        os.kill(args.pid, 9)

    time.sleep(0.8)

    env = os.environ.copy()
    env["STT_MODEL_ROOT"] = args.model_root
    env["STT_ENGINE"] = args.engine
    env["STT_MODEL"] = args.model

    stdout_file = open(args.stdout_log, "ab", buffering=0)
    stderr_file = open(args.stderr_log, "ab", buffering=0)
    subprocess.Popen(
        [
            args.python,
            "-u",
            str(server_script),
            "--host",
            "0.0.0.0",
            "--port",
            str(args.port),
            "--engine",
            args.engine,
            "--model",
            args.model,
        ],
        cwd=str(service_dir),
        env=env,
        stdin=subprocess.DEVNULL,
        stdout=stdout_file,
        stderr=stderr_file,
        close_fds=True,
        creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0) if os.name == "nt" else 0,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
