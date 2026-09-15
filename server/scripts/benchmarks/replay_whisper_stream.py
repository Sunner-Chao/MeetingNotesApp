"""Replay PCM WAV at realtime speed to the experimental resident probe over SSH."""
import argparse
import json
import os
from pathlib import Path
import struct
import threading
import time
import wave

import paramiko


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", required=True)
    parser.add_argument("--port", type=int, default=22)
    parser.add_argument("--user", required=True)
    parser.add_argument("--key")
    parser.add_argument("--password-env", help="Optional environment variable name, never the password")
    parser.add_argument("--known-hosts", help="Defaults to the user's SSH known_hosts")
    parser.add_argument("--remote-command", required=True, help="Explicit probe command for the remote shell")
    parser.add_argument("--wav", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--seconds", type=float, default=180)
    parser.add_argument("--drain-timeout", type=float, default=120)
    args = parser.parse_args()
    if not 0 < args.seconds <= 1800 or args.drain_timeout <= 0:
        parser.error("seconds must be in (0,1800]; drain timeout must be positive")
    with wave.open(str(args.wav), "rb") as source:
        if (source.getframerate(), source.getnchannels(), source.getsampwidth()) != (16000, 1, 2):
            parser.error("WAV must be uncompressed 16 kHz mono PCM16")
        pcm = source.readframes(min(source.getnframes(), int(args.seconds * 16000)))
    if not pcm:
        parser.error("WAV contains no samples")
    password = os.environ[args.password_env] if args.password_env else None
    client = paramiko.SSHClient()
    client.load_system_host_keys()
    client.load_host_keys(args.known_hosts or str(Path.home() / ".ssh" / "known_hosts"))
    channel = None
    readers = []
    with args.output.open("x", encoding="utf-8") as output:
        try:
            client.connect(args.host, port=args.port, username=args.user,
                           key_filename=args.key, password=password, timeout=15,
                           auth_timeout=20, allow_agent=not password, look_for_keys=not password)
            channel = client.get_transport().open_session(timeout=15)
            channel.settimeout(args.seconds + args.drain_timeout + 60)
            channel.exec_command(args.remote_command)
            ready, done = threading.Event(), threading.Event()
            state = {"started": None, "summary": None, "error": None}

            def consume_events():
                try:
                    for line in channel.makefile("rb"):
                        event = json.loads(line)
                        if event["type"] == "preview" and state["started"] is not None:
                            event["client_received_ms"] = round((time.monotonic() - state["started"]) * 1000, 2)
                            event["client_delay_ms"] = round(event["client_received_ms"] - event["end_ms"], 2)
                        output.write(json.dumps(event, ensure_ascii=False) + "\n")
                        output.flush()
                        if event["type"] == "ready":
                            ready.set()
                        elif event["type"] == "summary":
                            state["summary"] = event
                except Exception as exc:
                    state["error"] = type(exc).__name__
                finally:
                    done.set()

            def drain_diagnostics():
                # Drain separately so verbose OpenCL diagnostics cannot deadlock stdout.
                try:
                    for _ in channel.makefile_stderr("rb"):
                        pass
                except (OSError, EOFError):
                    pass

            readers = [threading.Thread(target=consume_events), threading.Thread(target=drain_diagnostics)]
            for thread in readers:
                thread.start()
            deadline = time.monotonic() + 60
            while not ready.wait(.1):
                if done.is_set() or time.monotonic() >= deadline:
                    raise RuntimeError("Resident probe did not report ready")
            state["started"] = time.monotonic()
            for offset in range(0, len(pcm), 3200):
                delay = state["started"] + offset / 32000 - time.monotonic()
                if delay > 0:
                    time.sleep(delay)
                frame = pcm[offset:offset + 3200]
                channel.sendall(struct.pack("<I", len(frame) // 2) + frame)
            channel.sendall(struct.pack("<I", 0))
            channel.shutdown_write()
            if not done.wait(args.drain_timeout):
                raise RuntimeError("Probe did not finish draining the recorded audio")
            if channel.recv_exit_status() or state["error"] or state["summary"] is None:
                raise RuntimeError("Probe exited without a valid successful summary")
            summary = state["summary"]
            if summary["failures"] or summary["bad_input"]:
                raise RuntimeError("Probe reported inference or input failures")
            print(json.dumps(summary))
        finally:
            if channel is not None:
                channel.close()
            for thread in readers:
                thread.join(5)
            client.close()


if __name__ == "__main__":
    main()
