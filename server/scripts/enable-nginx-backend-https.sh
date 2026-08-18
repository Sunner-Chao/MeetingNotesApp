#!/usr/bin/env bash
set -Eeuo pipefail

CONFIG="${NGINX_CONFIG:-/etc/nginx/sites-available/synthapi.conf}"
BACKUP="${CONFIG}.bak-meetingnotes-$(date -u +%Y%m%d%H%M%S)"
BACKEND_ORIGIN="${MEETINGNOTES_BACKEND_ORIGIN:-http://127.0.0.1:8090}"
STT_ORIGIN="${MEETINGNOTES_STT_ORIGIN:-http://127.0.0.1:8888}"

[[ "${EUID}" -eq 0 ]] || { echo "Run as root." >&2; exit 1; }
[[ -f "$CONFIG" ]] || { echo "Missing Nginx config: $CONFIG" >&2; exit 1; }
[[ "$BACKEND_ORIGIN" =~ ^https?://[^/]+$ ]] || { echo "Invalid MEETINGNOTES_BACKEND_ORIGIN." >&2; exit 1; }
[[ "$STT_ORIGIN" =~ ^https?://[^/]+$ ]] || { echo "Invalid MEETINGNOTES_STT_ORIGIN." >&2; exit 1; }

cp -a "$CONFIG" "$BACKUP"
python3 - "$CONFIG" "$BACKEND_ORIGIN" "$STT_ORIGIN" <<'PY'
from pathlib import Path
import re
import sys

path = Path(sys.argv[1])
backend_origin = sys.argv[2]
stt_origin = sys.argv[3]
text = path.read_text(encoding="utf-8")
begin_marker = "    # BEGIN MeetingNotesApp managed routes"
end_marker = "    # END MeetingNotesApp managed routes"
locations = """

BEGIN_MARKER
    location = /app {
        return 308 /app/;
    }

    location ^~ /app/ {
        proxy_pass BACKEND_ORIGIN;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
        proxy_set_header X-Forwarded-Host $host;
    }

    location = /web {
        proxy_pass BACKEND_ORIGIN/web;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header Authorization $http_authorization;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
        proxy_set_header X-Forwarded-Host $host;
    }

    location = /health {
        proxy_pass BACKEND_ORIGIN/health;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header Authorization $http_authorization;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
        proxy_set_header X-Forwarded-Host $host;
    }

    location ^~ /api/ {
        proxy_pass BACKEND_ORIGIN;
        proxy_http_version 1.1;
        proxy_connect_timeout 30s;
        proxy_send_timeout 660s;
        proxy_read_timeout 660s;
        proxy_set_header Host $host;
        proxy_set_header Authorization $http_authorization;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
        proxy_set_header X-Forwarded-Host $host;
    }

    location = /stt-cloud {
        return 308 /stt-cloud/;
    }

    location = /stt-cloud/ws/transcribe-stream {
        proxy_pass STT_ORIGIN/ws/transcribe-stream;
        proxy_http_version 1.1;
        proxy_read_timeout 86400s;
        proxy_send_timeout 3600s;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header Authorization $http_authorization;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
        proxy_set_header X-Forwarded-Host $host;
    }

    location ^~ /stt-cloud/ {
        client_max_body_size 1024m;
        proxy_pass STT_ORIGIN/;
        proxy_http_version 1.1;
        proxy_connect_timeout 30s;
        proxy_send_timeout 3600s;
        proxy_read_timeout 14400s;
        proxy_request_buffering off;
        proxy_buffering off;
        proxy_set_header Host $host;
        proxy_set_header Authorization $http_authorization;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
        proxy_set_header X-Forwarded-Host $host;
    }
END_MARKER
"""
locations = (
    locations.replace("BEGIN_MARKER", begin_marker)
    .replace("END_MARKER", end_marker)
    .replace("BACKEND_ORIGIN", backend_origin)
    .replace("STT_ORIGIN", stt_origin)
)


def remove_location(source: str, signature: str) -> str:
    while True:
        start = source.find(signature)
        if start < 0:
            return source
        line_start = source.rfind("\n", 0, start) + 1
        brace = source.find("{", start)
        if brace < 0:
            raise SystemExit(f"Malformed Nginx location: {signature}")
        depth = 0
        end = brace
        while end < len(source):
            if source[end] == "{":
                depth += 1
            elif source[end] == "}":
                depth -= 1
                if depth == 0:
                    end += 1
                    while end < len(source) and source[end] in " \t\r\n":
                        end += 1
                    source = source[:line_start] + source[end:]
                    break
            end += 1
        else:
            raise SystemExit(f"Unterminated Nginx location: {signature}")


managed = re.compile(
    re.escape(begin_marker) + r".*?" + re.escape(end_marker) + r"\s*",
    re.DOTALL,
)
text = managed.sub("", text)

# Upgrade the route block installed by older MeetingNotesApp releases.
if "MeetingNotesApp Backend: keep the application on localhost" in text:
    for signature in (
        "location = /web {",
        "location = /health {",
        "location ^~ /api/ {",
    ):
        text = remove_location(text, signature)
    text = re.sub(
        r"^[ \t]*# MeetingNotesApp Backend: keep the application on localhost.*?\n",
        "",
        text,
        flags=re.MULTILINE,
    )

certificate_key = re.search(r"^[ \t]*ssl_certificate_key\s+[^;]+;[ \t]*\n", text, re.MULTILINE)
if certificate_key is None:
    raise SystemExit("Could not find an HTTPS server certificate key directive")
insert_at = certificate_key.end()
text = text[:insert_at] + locations + text[insert_at:]

path.write_text(text, encoding="utf-8")
print(f"MeetingNotesApp HTTPS routes are up to date; backup={path}.bak-meetingnotes")
PY

nginx -t
systemctl unmask nginx.service
systemctl enable nginx.service
systemctl restart nginx.service
systemctl is-active nginx.service
echo "MeetingNotesApp HTTPS routes enabled: /app/, /api/, /health, /web and /stt-cloud/"
