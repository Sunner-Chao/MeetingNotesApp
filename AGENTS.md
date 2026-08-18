# MeetingNotesApp Working Notes

- Keep Android and Server source changes separated under `android/` and `server/`.
- Synchronize meaningful feature, architecture, deployment, validation, and incident changes to `E:\Notes\Notes\10-项目\01-产品研发\MeetingNotesApp（智悟本）`.
- Keep the Obsidian folder hierarchy and indexes current when adding notes.
- Never write API tokens, SSH passwords, web credentials, or other secrets into source, generated documents, logs, or Obsidian notes.
- Treat `server/knowledge/templates/` as a versioned template registry and `server/knowledge/documents/` as future RAG source material. Do not vectorize DOCX layout binaries as ordinary knowledge documents.

## Android release policy

- Ship Android upgrades through the server-managed OTA channel. Do not distribute temporary debug APKs as product releases.
- Build the fixed-signature `release` variant, verify its pinned certificate and SHA-256, then publish the versioned APK and update manifest atomically.
- Retain exactly two server APKs: the latest release and the immediately preceding release. Remove older APKs only after the new release and metadata have passed health checks.
- Keep Android update endpoints, server addresses, SSH keys and remote paths in environment/configuration or deployment-state inputs; never hardcode credentials or machine-specific release settings in application source.
- Verify the public metadata endpoint and versioned APK endpoint after every release. Android must discover the release on login or foreground resume and present the standard update prompt.
