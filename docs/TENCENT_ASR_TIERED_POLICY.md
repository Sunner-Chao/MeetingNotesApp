# Tencent ASR Tiered Policy

## Production policy

The application exposes two explicit Tencent ASR tiers.

| Tier | Android model | Realtime provider | Tencent engine | Production state |
| --- | --- | --- | --- | --- |
| Tencent Standard Cloud (free allowance) | `tencent-standard` | `tencent-realtime-standard` | `16k_zh` | Enabled; provider resource pack determines available usage |
| Tencent Precision Cloud (paid) | `tencent-precision` | `tencent-realtime-precision` | `16k_zh_en` | Disabled, monthly cap is zero |

Old clients using `tencent-flash` or `tencent-realtime` are always mapped to the standard tier. They cannot reach the precision tier.

## Server enforcement

- Before a recording-file request, the STT server probes its duration and reserves that duration atomically in SQLite.
- A realtime session does not reserve or deduct an application-side monthly budget. The bridge forwards PCM until the user stops the meeting, the connection fails, Tencent ends the session, or the service concurrency limit is reached.
- Cancellation, disconnects, and bridge failures close the Tencent WebSocket immediately. There is no application-side 30-minute session limit or realtime monthly quota limit.
- The standard/free tier does not use the application budget ledger for either realtime or recording-file recognition. Only the opt-in precision/paid tier keeps the separate recording-file duration ledger; this never limits standard meetings.
- Precision requires both its enable flags and a positive `TENCENT_PRECISION_MONTHLY_LIMIT_SEC`; either condition missing keeps it unavailable.
- `/cloud-asr/policy` is the authoritative server budget view. Tencent `GetUsageByDate` data is retained only as a delayed account-level reference and must not be presented as a billing guarantee.

## Tencent console safeguard

The application cannot alter Tencent Cloud billing settings. Disable postpaid for both standard Tencent ASR products in the Tencent console after confirming the intended free resource packs:

1. Real-time Speech Recognition
2. Recording File Recognition Flash Edition

When Tencent returns quota-exhausted error `4004`, the server falls back to Faster-Whisper. This is the final provider-side protection against charges after free quota exhaustion.

## Production verification

Release `1.2.11` uses the following production policy:

- Standard: enabled, `16k_zh`; realtime and file recognition have no application-side duration or monthly quota limit.
- Precision: disabled, `16k_zh_en`, zero cap.
- Authenticated model list: only `tencent-standard` is available.
- Existing 182 seconds of legacy `16k_zh_en` usage is attributed to the disabled precision tier, not to standard free quota.
