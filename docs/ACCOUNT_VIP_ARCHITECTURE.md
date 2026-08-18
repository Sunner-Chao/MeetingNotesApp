# Account and VIP Architecture

## Database tables

| Table | Responsibility |
| --- | --- |
| `users` | Stable account record; legacy username/scrypt credentials remain compatible |
| `account_identities` | Verified email, phone, password, WeChat, and team Feishu identities |
| `auth_verification_codes` | Hashed one-time codes, expiry, cooldown, attempts, and consumption state |
| `user_sessions` | Hashed expiring Bearer sessions |
| `user_entitlements` | VIP, construction templates, granted quota |
| `account_plans` | Runtime-configured minutes, AI Credits, seats, price, and legacy quota fields |
| `recharge_orders` | Pending/approved/rejected recharge workflow |
| `agent_tokens` | Enforced per-user Agent quota |
| `account_usage_balances` | Current-cycle STT seconds, AI Credits, and team seats |
| `account_usage_events` | Idempotent STT/AI reservation and settlement ledger |
| `account_teams` | Team owner and team-level identity |
| `account_team_members` | Team membership and seat occupancy |

New ordinary users receive 120 STT minutes and 5 AI Credits by default. The legacy 10-request fields remain populated so older Android/PWA builds continue to render and authenticate while clients migrate to the usage summary.

## Authentication policy

- Phone and email verification codes are the primary mobile entry points. Verification creates an account on first use and logs into an existing identity on later use.
- WeChat is the consumer quick-login provider. QQ is intentionally omitted. Feishu is returned with `tier=team` and is not shown in the ordinary consumer registration UI.
- Username/password registration and login remain available for legacy accounts and exceptional/offline recovery flows.
- Verification codes have isolated `login`, `bind`, and `reset_password` purposes. A code issued for one purpose cannot be replayed for another.
- A signed-in user may bind another verified phone or email unless that identity already belongs to another account.
- Password reset is available for an existing phone/email identity. A successful reset replaces the scrypt credential and revokes existing user sessions and Agent access.
- A guest may enter the Android workspace and record locally. Streaming and final STT stay disabled until a valid account session exists.
- On successful login, Android keeps its Room database and audio files, then asynchronously upserts local meetings, transcripts, and reports into the account namespace.

Production must configure `ACCOUNT_AUTH_CODE_WEBHOOK_URL` for the private SMS/email delivery adapter. `ACCOUNT_AUTH_CODE_DEBUG` defaults to false; verification codes must never be returned, logged, or stored in plaintext in production.

## Usage settlement

- STT checks that some balance remains before proxying, then records actual rounded-up seconds only after a successful upstream response containing `duration_ms`.
- Android sends `X-Usage-Key`; retries with the same key return the existing ledger event instead of charging twice.
- Agent requests reserve one AI Credit before provider execution. Provider, timeout, attachment, or queue failures refund the reservation.
- The first report generation for a meeting is charged. The next three successful regenerations within 24 hours are free; later regenerations charge again. Successful chat requests charge one Credit.
- `usage_key` is unique across the ledger. A completed duplicate returns the cached Agent result where available.
- Approved plans have a real `duration_days` subscription period. Early renewal extends the current expiry instead of discarding remaining time.
- Expired subscriptions are refreshed back to Free before usage checks: VIP/template access is disabled, Free quotas are restored for the new cycle, and extra team members are removed.
- Manual recharge approval grants the plan's minutes, Credits, and seats exactly once. Payment settlement remains a separate future integration.

## Team plans

- The team owner consumes one seat. Membership writes are rejected when the configured seat limit is reached.
- A user can belong to only one team. Owners cannot remove themselves through the member endpoint.
- `team_standard` is a 30-day plan with 6,000 STT minutes, 300 AI Credits, and five seats.
- The service currently exposes owner-authenticated team read/add/remove APIs. Android shows the active seat allowance but does not expose user-ID invitations; member discovery by phone/email requires an explicit privacy and consent policy first.

## Security

- Login and registration return a short-lived, per-user HMAC STT token alongside the account and Agent credentials.
- `/api/account/session` refreshes the profile and runtime credentials after an administrator approves an order.
- The STT process validates signed user tokens with `ACCOUNT_TOKEN_SECRET`; `STT_API_TOKEN` remains server-only for management and compatibility.

- Passwords use `hashlib.scrypt` with a random 16-byte salt.
- Session and Agent tokens are stored as SHA-256 hashes server-side.
- Per-user Agent tokens are derived by HMAC from `ACCOUNT_TOKEN_SECRET` and user ID.
- Agent-token expiry follows the user's longest active login session; logout of the last session expires Agent access.
- Disabling a user deletes all sessions and disables the matching Agent token.
- Admin bootstrap uses `ACCOUNT_ADMIN_USERNAME` and `ACCOUNT_ADMIN_PASSWORD` from the deployment environment.
- Account admin routes require a database user whose role is `admin`.
- Administrators may permanently delete ordinary users. The transaction removes sessions, entitlements, recharge orders, the per-user Agent token, and Agent task rows; administrator accounts cannot be deleted through this API.
- Repeated order approval is rejected and cannot grant quota twice.
- Verification codes are HMAC-hashed with the account secret, expire, are invalidated when replaced, and stop accepting guesses after the configured attempt limit.

## Commercial boundary

The implemented recharge workflow is manual approval, not payment settlement. A payment provider can later create and settle the same order only through a provider-signed callback. No current endpoint treats a client-side click as paid, and missing merchant configuration must never fall back to simulated success.
