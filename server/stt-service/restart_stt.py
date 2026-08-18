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

    # Ensure ffmpeg is on PATH for compressed audio decoding.
    found_ffmpeg = False
    for ffmpeg_dir in [
        os.path.join(os.environ.get("LOCALAPPDATA", os.path.expanduser("~\\AppData\\Local")),
                     "Microsoft", "WinGet", "Links"),
        os.path.join(os.environ.get("LOCALAPPDATA", os.path.expanduser("~\\AppData\\Local")),
                     "Microsoft", "WinGet", "Packages",
                     "Gyan.FFmpeg_Microsoft.Winget.Source_8wekyb3d8bbwe", "ffmpeg-8.1.1-full_build", "bin"),
        os.path.join(os.environ.get("ProgramFiles", "C:\\Program Files"), "ffmpeg", "bin"),
        os.path.join(os.environ.get("ProgramFiles(x86)", "C:\\Program Files (x86)"), "ffmpeg", "bin"),
        "D:\\ffmpeg\\bin",
    ]:
        import glob as _glob
        candidates = _glob.glob(ffmpeg_dir) if "*" in ffmpeg_dir else ([ffmpeg_dir] if os.path.isdir(ffmpeg_dir) else [])
        for candidate in candidates:
            if os.path.isdir(candidate) and os.path.isfile(os.path.join(candidate, "ffmpeg.exe")):
                env["PATH"] = candidate + os.pathsep + env.get("PATH", "")
                print(f"[restart_stt] Added ffmpeg to PATH from: {candidate}", flush=True)
                found_ffmpeg = True
                break
        if found_ffmpeg:
            break
    if not found_ffmpeg:
        print("[restart_stt] WARNING: ffmpeg not found - some compressed audio may fail to load!", flush=True)

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
