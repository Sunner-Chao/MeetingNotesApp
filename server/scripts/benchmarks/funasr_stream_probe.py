"""Resident, incremental Paraformer probe using the framed-PCM benchmark protocol."""
import argparse
import json
import os
import struct
import sys
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", required=True)
    parser.add_argument("--device", default="cuda:0")
    parser.add_argument("--chunk", type=int, choices=(5, 10), default=5)
    args = parser.parse_args()
    os.environ.setdefault("CUDA_VISIBLE_DEVICES", "0")
    sys.stdout.reconfigure(encoding="utf-8", errors="strict")
    protocol_output = sys.stdout
    # Third-party libraries print startup and progress on stdout; reserve the
    # protocol channel for JSON and keep those diagnostics on stderr.
    sys.stdout = sys.stderr
    import numpy as np
    import torch
    from funasr import AutoModel

    torch.set_num_threads(4)
    if args.device.startswith("cuda") and not torch.cuda.is_available():
        raise RuntimeError("CUDA required for this run; CPU fallback is disabled")

    def emit(value):
        protocol_output.write(json.dumps(value, ensure_ascii=False) + "\n")
        protocol_output.flush()

    load_start = time.perf_counter()
    model = AutoModel(model=args.model, device=args.device, disable_update=True,
                      disable_pbar=True, disable_log=True)
    chunk_size = [0, args.chunk, 5]
    chunk_samples = args.chunk * 960

    def generate(audio, cache, final):
        return model.generate(input=audio, cache=cache, is_final=final,
                              chunk_size=chunk_size, encoder_chunk_look_back=4,
                              decoder_chunk_look_back=1, disable_pbar=True)

    # Warm up before ready, as a production resident service would do.
    generate(np.zeros(chunk_samples, dtype=np.float32), {}, True)
    if args.device.startswith("cuda"):
        torch.cuda.synchronize()
    emit({"type": "ready", "backend": "funasr-paraformer-online",
          "device": args.device, "torch": torch.__version__,
          "cuda": torch.version.cuda, "chunk_ms": args.chunk * 60,
          "load_ms": round((time.perf_counter() - load_start) * 1000, 3)})
    cache, buffer, transcript = {}, bytearray(), ""
    started = None
    consumed = samples_seen = updates = 0
    costs = []
    first_ms = None

    def decode(pcm, final=False):
        nonlocal consumed, transcript, updates, first_ms
        audio = np.frombuffer(pcm, dtype="<i2").astype(np.float32) / 32768.0
        t = time.perf_counter()
        result = generate(audio, cache, final)
        if args.device.startswith("cuda"):
            torch.cuda.synchronize()
        cost = (time.perf_counter() - t) * 1000
        costs.append(cost)
        consumed += len(audio)
        fragment = result[0].get("text", "") if result else ""
        if fragment:
            # Online Paraformer emits short deltas, but may repeat a suffix
            # while revising a chunk. Merge the largest suffix/prefix overlap.
            overlap = min(len(transcript), len(fragment))
            while overlap and not transcript.endswith(fragment[:overlap]):
                overlap -= 1
            transcript += fragment[overlap:]
            elapsed = (time.perf_counter() - started) * 1000
            if first_ms is None:
                first_ms = elapsed
            updates += 1
            emit({"type": "preview", "end_ms": consumed / 16,
                  "server_elapsed_ms": round(elapsed, 3),
                  "inference_ms": round(cost, 3), "text": transcript})

    source = sys.stdin.buffer
    while True:
        header = source.read(4)
        if len(header) != 4:
            raise RuntimeError("Truncated frame header")
        count, = struct.unpack("<I", header)
        if count == 0:
            break
        if count > 16000:
            raise RuntimeError("Oversized frame")
        frame = source.read(count * 2)
        if len(frame) != count * 2:
            raise RuntimeError("Truncated PCM")
        if started is None:
            started = time.perf_counter()
        samples_seen += count
        buffer.extend(frame)
        while len(buffer) >= chunk_samples * 2:
            pcm = bytes(buffer[:chunk_samples * 2])
            del buffer[:chunk_samples * 2]
            decode(pcm)
    if started is None:
        raise RuntimeError("No audio received")
    decode(bytes(buffer), final=True)
    emit({"type": "summary", "audio_ms": samples_seen / 16,
          "elapsed_server_ms": round((time.perf_counter() - started) * 1000, 3),
          "first_partial_server_ms": first_ms, "updates": updates,
          "decode_steps": len(costs), "decode_total_ms": sum(costs),
          "decode_mean_ms": sum(costs) / len(costs), "decode_max_ms": max(costs),
          "gpu_peak_mb": torch.cuda.max_memory_allocated() / 1048576 if args.device.startswith("cuda") else 0,
          "final_text": transcript, "failures": 0, "bad_input": False})


if __name__ == "__main__":
    main()
