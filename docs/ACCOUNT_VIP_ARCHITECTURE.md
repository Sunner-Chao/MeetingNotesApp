# Account and VIP Architecture

## Database tables

| Table | Responsibility |
| --- | --- |
| `users` | Username, scrypt hash, role, enabled state |
| `user_sessions` | Hashed expiring Bearer sessions |
| `user_entitlements` | VIP, construction templates, granted quota |
| `account_plans` | Runtime-configured price and quota packages |
| `recharge_orders` | Pending/approved/rejected recharge workflow |
| `agent_tokens` | Enforced per-user Agent quota |

New ordinary users receive the runtime-configured `Free` plan with 10 total trial requests. Service initialization raises legacy non-VIP users below that floor to 10 without resetting consumed requests or repeatedly adding quota.

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

## Commercial boundary

The implemented recharge workflow is manual approval, not payment settlement. A payment provider can later create/approve the same order through a verified callback, but no current endpoint treats a client-side click as paid.
