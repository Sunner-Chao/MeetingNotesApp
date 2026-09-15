# GE4 resident Whisper preview benchmark

This is an experimental inference probe, not an Android STT endpoint. It does not provide account authentication, WebSocket sessions, speaker labels, VAD, transcript merging, or production service management.

The probe keeps a model in memory and reads framed 16 kHz mono PCM16LE from standard input. A receiving thread continues accepting audio while the inference thread processes consecutive windows. It processes every step in order instead of dropping old windows to hide overload. Results are emitted as JSON Lines to standard output; library diagnostics go to standard error.

## Build prerequisites

- whisper.cpp v1.5.4, static library, SSE3/SSSE3 enabled; AVX, AVX2, F16C and FMA disabled for i5-650.
- CLBlast 1.6.3, static library, `AMD_SI_EMPTY_KERNEL_WORKAROUND=ON`.
- OpenCL headers/import library and MSVC static runtime (`/MT`). The CUDA toolkit is unnecessary at runtime; its OpenCL import library was used for the local cross-build only.
- Windows 7-compatible executable, linked with `whisper.lib`, `clblast.lib`, `OpenCL.lib` and `psapi.lib`.

The GE4 lab copies need two compatibility patches: binary16 storage with FP32 conversion in ggml's OpenCL kernels (replace packed half fields with 16-bit integer storage), and optional `CL_MEM_ALLOC_HOST_PTR` allocations in **both** ggml and CLBlast. The checked experimental sources remain in `tmp/ge4-build`; this file alone is not a reproducible package of those third-party dependencies. A clean rebuild of CLBlast is required after its header change. An old object cache produced invalid matrix results before that rebuild.

## Protocol and parameters

```
whisper_stream_probe.exe MODEL STEP_MS WINDOW_MS THREADS AUDIO_CTX PROMPT
```

`PROMPT=none` disables prompting. `AUDIO_CTX=0` uses the model's original 30-second context. `AUDIO_CTX=-1` sizes each invocation to the audio window plus one second, rounded up to 128 encoder positions (max 1500). Smaller contexts are experimental and can affect accuracy.

Set `GGML_OPENCL_PINNED_MEMORY=1` for the patched GE4 libraries. `GGML_OPENCL_GE4_COMPAT=1` forces direct GEMM and an ordered queue in the lab build; it is unnecessary in the successful clean-rebuild results.

Wait for the `ready` JSON event before sending audio. Each frame consists of an unsigned little-endian 32-bit sample count, then that many signed 16-bit samples. At most 16000 samples per frame. Send a zero count to end input. Use 1600-sample frames every 100 ms for realtime playback; send times must follow a monotonic deadline, not inference completion.

Example candidate: `MODEL 4000 6000 4 -1 none`.

`replay_whisper_stream.py` sends the 100 ms frames over verified-host SSH and records arrival latency at the sending computer. Supply host, user, key or a password **environment variable name**, and the remote command through CLI inputs; none are embedded in source. It accepts a mono 16 kHz PCM16 WAV and creates a new JSONL output. Run `python replay_whisper_stream.py --help` for arguments. Do not redirect the remote probe's stdout into a remote file, because the replay client must receive its `ready` event. This SSH test is not an Android/WSS acceptance test.

## Metrics and limits

- `wall_ms`: elapsed from the first frame received at GE4.
- `delay_ms`: emission time minus audio window end; this excludes network return time.
- `backlog_ms`: audio already received but not yet processed, measured at emission.
- `inference_ms`: elapsed time for the current window.
- `rss_mb`: total process working set; this includes the probe's accumulated test audio.
- `text`: replaceable text for `[start_ms,end_ms]`; overlapping events must **not** simply be appended together.

The test probe retains input for analysis, up to 30 minutes, and is deliberately not a production ring buffer. `max_tokens=128` bounds each decode. Zero process failures does not establish transcription accuracy, lack of omissions, long-term memory stability, or Android/WebSocket availability. Timing starts after model initialization; report the `ready.load_ms` cold-load time separately.

2026-09-15 results and deployment status: [GE4 realtime preview validation](../../../docs/architecture/GE4实时预览实测与本机STT停用-20260915.md).
