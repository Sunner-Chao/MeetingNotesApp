#!/usr/bin/env python3
"""Compare CPU preview profiles without printing transcript content."""

from __future__ import annotations

import argparse
import difflib
import gc
import json
import re
import tempfile
import time
import wave
from pathlib import Path

from faster_whisper import WhisperModel


def compact(text: str) -> str:
    return re.sub(r"\s+", "", text)


def transcribe(model: WhisperModel, audio: Path, beam_size: int) -> tuple[str, float]:
    started = time.monotonic()
    segments, _ = model.transcribe(
        str(audio),
        beam_size=beam_size,
        vad_filter=True,
        language="zh",
        condition_on_previous_text=False,
    )
    text = " ".join(segment.text.strip() for segment in segments if segment.text.strip())
    return compact(text), time.monotonic() - started


def write_prefix(source: Path, target: Path, duration_sec: float) -> None:
    with wave.open(str(source), "rb") as reader:
        frame_count = min(reader.getnframes(), int(reader.getframerate() * duration_sec))
        params = reader.getparams()
        frames = reader.readframes(frame_count)
    with wave.open(str(target), "wb") as writer:
        writer.setparams(params)
        writer.writeframes(frames)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("audio", type=Path)
    parser.add_argument("--model-root", type=Path, required=True)
    parser.add_argument("--cpu-threads", type=int, default=2)
    parser.add_argument("--windows", default="full")
    args = parser.parse_args()

    profiles = (("tiny", 1), ("small", 1), ("small", 5))
    results: list[dict[str, object]] = []
    texts: dict[tuple[str, str], str] = {}

    with tempfile.TemporaryDirectory(prefix="meetingnotes-preview-") as directory:
        sample_paths: list[tuple[str, Path]] = []
        for value in (item.strip() for item in args.windows.split(",")):
            if value == "full":
                sample_paths.append(("full", args.audio))
                continue
            duration = float(value)
            target = Path(directory) / f"prefix-{duration:g}.wav"
            write_prefix(args.audio, target, duration)
            sample_paths.append((f"{duration:g}s", target))

        if not any(label == "full" for label, _ in sample_paths):
            sample_paths.append(("full", args.audio))

        for model_name, beam_size in profiles:
            model = WhisperModel(
                str(args.model_root / model_name),
                device="cpu",
                compute_type="int8",
                cpu_threads=args.cpu_threads,
                num_workers=1,
                local_files_only=True,
            )
            profile_name = f"{model_name}-beam{beam_size}"
            for window, sample_path in sample_paths:
                text, elapsed = transcribe(model, sample_path, beam_size)
                texts[(profile_name, window)] = text
                results.append(
                    {
                        "profile": profile_name,
                        "window": window,
                        "elapsed_sec": round(elapsed, 3),
                        "chars": len(text),
                    }
                )
            del model
            gc.collect()

    baseline = texts[("small-beam5", "full")]
    for result in results:
        candidate = texts[(str(result["profile"]), str(result["window"]))]
        result["similarity_to_final"] = round(
            difflib.SequenceMatcher(None, candidate, baseline).ratio(), 3
        )
        result["is_final_substring"] = bool(candidate and candidate in baseline)

    print(json.dumps({"audio_bytes": args.audio.stat().st_size, "results": results}, indent=2))


if __name__ == "__main__":
    main()
