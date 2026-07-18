#!/usr/bin/env bash
set -Eeuo pipefail

CONFIG="/etc/nginx/sites-available/synthapi.conf"
BACKUP="${CONFIG}.bak-meetingnotes-$(date -u +%Y%m%d%H%M%S)"
CERT="/etc/letsencrypt/live/118.25.43.185/fullchain.pem"
KEY="/etc/letsencrypt/live/118.25.43.185/privkey.pem"

[[ "${EUID}" -eq 0 ]] || { echo "Run as root." >&2; exit 1; }
[[ -f "$CONFIG" ]] || { echo "Missing Nginx config: $CONFIG" >&2; exit 1; }
[[ -f "$CERT" && -f "$KEY" ]] || { echo "The IP certificate is missing." >&2; exit 1; }

cp -a "$CONFIG" "$BACKUP"
python3 - "$CONFIG" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
text = path.read_text(encoding="utf-8")
marker = "    ssl_certificate_key /etc/letsencrypt/live/118.25.43.185/privkey.pem;\n"
locations = """

    # MeetingNotesApp Backend: keep the application on localhost and publish only these routes over HTTPS.
    location = /web {
        proxy_pass http://127.0.0.1:8090/web;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header Authorization $http_authorization;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
        proxy_set_header X-Forwarded-Host $host;
    }

    location = /health {
        proxy_pass http://127.0.0.1:8090/health;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header Authorization $http_authorization;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
        proxy_set_header X-Forwarded-Host $host;
    }

    location ^~ /api/ {
        proxy_pass http://127.0.0.1:8090;
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
"""
install_marker = "MeetingNotesApp Backend: keep the application on localhost"
if install_marker not in text:
    if marker not in text:
        raise SystemExit("Could not find the IP HTTPS server certificate marker")
    text = text.replace(marker, marker + locations, 1)
else:
    api_start = "    location ^~ /api/ {\n"
    start = text.find(api_start)
    if start < 0:
        raise SystemExit("Could not find the installed MeetingNotesApp API location")
    end = text.find("    }\n", start)
    if end < 0:
        raise SystemExit("Could not find the end of the MeetingNotesApp API location")
    api_block = text[start:end]
    timeout_lines = (
        "        proxy_connect_timeout 30s;\n"
        "        proxy_send_timeout 660s;\n"
        "        proxy_read_timeout 660s;\n"
    )
    if "proxy_read_timeout" not in api_block:
        api_block = api_block.replace(
            "        proxy_http_version 1.1;\n",
            "        proxy_http_version 1.1;\n" + timeout_lines,
            1,
        )
        text = text[:start] + api_block + text[end:]

path.write_text(text, encoding="utf-8")
print(f"MeetingNotesApp HTTPS locations are up to date; backup={path}.bak-meetingnotes")
PY

nginx -t
systemctl unmask nginx.service
systemctl enable nginx.service
systemctl restart nginx.service
systemctl is-active nginx.service
echo "HTTPS dashboard: https://118.25.43.185/web"
