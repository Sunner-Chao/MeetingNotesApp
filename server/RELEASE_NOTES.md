# MeetingNotesApp Server 1.1.11

This native Ubuntu release keeps revisable preview text cumulative while reducing CPU pressure on the 4-core host.

## Changes from 1.1.10

- Continues revisable preview text across overlapping windows instead of replacing the visible history with only the latest candidate.
- Uses an 8-second snapshot, 4-second overlap, and 4-second step; `small + beam=1` stays ahead of real-time audio without the accuracy loss measured for `tiny`.
- Keeps a two-character provisional candidate available, with VAD, confidence, no-speech and hallucination safeguards; completed files still use `small + beam=5`.
- Release: `1.1.11-20260718023549710`

## 1.1.10

This native Ubuntu release restores visible revisable live preview while keeping the completed transcript on the final high-accuracy decoder.

## Changes from 1.1.9

- Uses the pinned `small` model with a no-retry `beam_size=1` decoder for preview; this avoids the final short-result retry blocking live updates.
- Starts preview after two seconds of audio and advances the rolling window every two seconds, with up to sixteen seconds of context and eight seconds of overlap.
- Allows a two-character high-confidence candidate as replaceable preview text; only repeated stable candidates are merged into the confirmed preview buffer.
- Keeps VAD, no-speech filtering, hallucination suppression, Simplified-Chinese normalization, and the final `small + beam_size=5` full-file transcription unchanged.
- Adds a privacy-safe CPU benchmark for comparing preview profiles without printing meeting content.
- Release: `1.1.10-20260718015538178`

## 1.1.9

This native Ubuntu release prevents short high-confidence fragments from appearing as noisy live preview text.

## Changes from 1.1.8

- Requires at least four effective characters for both confidence-gated and stability-gated preview output.
- Keeps shorter fragments in the visible processing state instead of rendering them as transcript text.
- Adds regression coverage for a high-confidence two-character fragment.
- Release: `1.1.9-20260718012524338`

## 1.1.8

This native Ubuntu release makes live preview and final file transcription share one Faster-Whisper decoding strategy.

## Changes from 1.1.7

- Uses the same `small` model instance or model artifact for preview and final transcription.
- Routes both paths through one decoder: beam size 5, VAD enabled, Chinese language, and no previous-text conditioning.
- Applies the same short-result retry, hallucination suppression, whitespace cleanup, and Simplified-Chinese conversion.
- Keeps confidence and cross-window stability as a display-only quality gate; neither changes model decoding nor affects the final transcript.
- Treats each bounded-window result as replaceable preview text; only the completed file transcription is authoritative.
- Waits for at least eight seconds of audio before the first high-accuracy preview inference.
- Holds unstable low-confidence candidates instead of rendering likely noise or misrecognition.
- Release: `1.1.8-20260718011120910`

## 1.1.7

This native Ubuntu release restores visible live transcription without allowing low-confidence preview text into the final transcript.

## Changes from 1.1.6

- Keeps strict segment filtering as the source of committed streaming text.
- After consecutive fully rejected windows, emits the current decoded window as replaceable `preview_text` only.
- Never merges fallback preview text into committed text or the final file transcription.
- Continues to suppress known streaming hallucination phrases.
- Adds regression coverage for the rejection threshold, Simplified-Chinese normalization, and hallucination suppression.
- Release: `1.1.7-20260717103556084`

## 1.1.6

This native Ubuntu release makes every Agent CLI invocation use a fresh, non-persistent session.

## Changes from 1.1.5

- Keeps Codex requests isolated with `codex exec --ephemeral` and an independent task directory.
- Gives every Claude request a newly generated UUID through `--session-id`.
- Keeps Claude session history off disk with `--no-session-persistence`.
- Adds regression coverage that rejects reused Claude session IDs and missing Codex ephemeral mode.
- Release: `1.1.6-20260717100508267`

## 1.1.5

This native Ubuntu release makes final Simplified-Chinese transcription and image report requests reliable.

## Changes from 1.1.4

- Extracts structured Claude CLI errors from `stream-json` stdout when stderr is empty.
- Falls back from a failed Claude image task to Codex when the request token permits Codex.
- Keeps text-only Claude requests on the explicitly selected provider.
- Release: `1.1.5-20260717092406537`

## Changes from 1.1.3

- Applies OpenCC to the final Faster-Whisper HTTP response, not only WebSocket previews.
- Extends the Nginx `/api/` upstream timeout to 660 seconds for Agent image reports.
- Marks orphaned queued/running Agent tasks as failed after a service restart.
- Keeps the bounded Agent worker profile for the 4-core/4-GB host.

## Frozen validation

- Release: `1.1.4-20260717091437148`
- Server tests: 25 passed
- Final STT and streaming STT both return Simplified Chinese
- Nginx Agent route timeout: 660 seconds
- Codex and Claude image adapters remain covered by tests

## Changes from 1.1.2

- Reuses the loaded Faster-Whisper `small` model for live preview and final transcription.
- Commits only the settled first half of overlapping windows; the unstable tail is decoded again with future context.
- Rejects low-confidence and high no-speech segments instead of failing open with raw model output.
- Normalizes STT preview and final output to Simplified Chinese on the server.
- Fixes Claude CLI image requests by using bidirectional `stream-json` and parsing the final result event.
- Maps Android Agent HTTP errors to actionable Chinese messages.

## 1.1.3 validation

- Release: `1.1.3-20260717080729073`
- Host: Ubuntu 22.04, 4 CPU, 3719 MB RAM, no GPU
- Real-time replay: 78.46 seconds of phone audio, 4 accepted preview updates
- Preview/final lengths: 30/26 characters; edit similarity improved from 7.2% to 67.9%
- Average/max preview inference: 2.92/12.48 seconds in local cold-state replay
- Codex image report and Claude stream-output adapter tests pass

## Changes included from 1.1.1

- Allows Codex CLI to load the isolated service account's custom provider configuration.
- Supports explicit relay credential environment variables for Codex CLI and Claude CLI.
- Reports relay-backed providers as authenticated without requiring first-party OAuth.
- Keeps relay keys in the root-managed production environment file and out of the release archive.

## Agent gateway included since 1.1.0

- Adds `/api/agent` with independent Bearer authentication.
- Adds per-token request quotas, provider permissions, expiry and disable controls.
- Adds a bounded single-worker Agent queue for the 4-core/4-GB server profile.
- Adds Codex CLI and Claude CLI adapters with image attachment support.
- Adds private task history and quota/health endpoints.
- Adds administrator token issue/list/disable endpoints under existing Web authentication.

## Runtime profile

- Faster-Whisper `small`, CPU `int8`
- Two concurrent inference slots with two CPU threads each
- Bounded FIFO queue with 16 waiting jobs
- One application process so the model is loaded only once
- systemd limits: 3 GB memory, 350% CPU, 128 tasks
- Backend Service enabled behind Nginx HTTPS
- Agent queue: one active CLI task and eight waiting tasks

## Security

- Backend port 8090 is localhost-only and should not be opened in the cloud security group.
- Nginx handles HTTPS and forwards `/web`, `/health` and `/api/*` to Backend.
- Web login uses `ubuntu` plus the separate Web token. SSH keys/passwords remain server access credentials only.
- Agent tokens are hashed in SQLite; plaintext is returned only when issued.
- CLI processes run as the isolated `meetingnotes` service account. OAuth credentials are never copied from the SSH account; explicitly configured relay API credentials are provisioned through the root-managed environment file.
