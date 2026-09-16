# V100 FunASR STT service

This directory is the V100 deployment unit. It runs on `DESKTOP-H3OE2CM` and
keeps the models outside the repository:

- real-time preview: Paraformer-Large online;
- file transcription: Paraformer-Large offline, with FSMN VAD and CT-Transformer punctuation;
- future diarization slot: CAM++ can be added as a separate worker without changing the STT wire protocol.

The service listens on `V100_PORT` (default `8889`). It accepts either the
existing management token or an account-scoped token signed with the backend's
`ACCOUNT_TOKEN_SECRET`; both are deployment-only environment values. Android
sends its bearer token in the WebSocket handshake, while browser clients may
use the one-time `authenticate` message.

`/health` reports `models_present`, `model_loaded`, `active`, and a reserved
CAM++ diarization slot. `/debug/stream-events` is account-scoped and never
stores transcript text or credentials. Audio upload files are deleted after
inference.
Tencent Cloud remains the application fallback and is not configured here.

The V100 host is currently reachable through Tailscale at `100.89.195.103`.
Production traffic reaches it through a restricted reverse SSH tunnel:
the VPS listens only on `127.0.0.1:18889`, and its `/stt-local` IPv4/IPv6
routes proxy to that loopback listener. The tunnel is maintained by the
`MeetingNotesApp-V100-ReverseTunnel` startup task on the V100 host; the
service itself is maintained by `MeetingNotesApp-V100-STT`. Do not expose
the Tailscale address or the relay port directly to the public Internet.

The complete local STT surface now lives on V100. The public operations page is
`https://lstwin.space/stt-admin/` (HTTP Basic credentials are deployment-only
`V100_ADMIN_USERNAME` / `V100_ADMIN_TOKEN`), and it proxies to the V100
`/admin/` page through the same loopback tunnel. `/stt-local/health`,
`/stt-local/ready`, `/stt-local/transcribe` and
`/stt-local/ws/transcribe-stream` all terminate at the V100 service. The old
Windows Faster-Whisper service is no longer part of the production route.
