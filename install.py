#!/usr/bin/env python3
"""Install the per-user desktop launcher and background service."""

from pathlib import Path
import json
import secrets
import subprocess


project = Path(__file__).resolve().parent
home = Path.home()
state = home / ".local/state/redmi-audio"
state.mkdir(parents=True, exist_ok=True)
state.chmod(0o700)
token = state / "token"
if not token.exists():
    token.write_text(secrets.token_urlsafe(18))
    token.chmod(0o600)

config = state / "config.json"
if not config.exists():
    sinks = subprocess.run(["pactl", "list", "short", "sinks"],
                           capture_output=True, text=True, check=True).stdout
    physical = next((line.split("\t")[1] for line in sinks.splitlines()
                     if "\tredmi_phone\t" not in line), "")
    config.write_text(json.dumps({"reverse_sink": physical, "previous_sink": physical,
                                  "pc_audio": True, "volume": 100,
                                  "connection": "direct"}, indent=2))

units = home / ".config/systemd/user"
units.mkdir(parents=True, exist_ok=True)
(units / "redmi-audio.service").write_text(f"""[Unit]
Description=Redmi Audio bridge
After=pipewire-pulse.service

[Service]
ExecStart=/usr/bin/python3 {project / 'server.py'}
Restart=on-failure

[Install]
WantedBy=default.target
""")
subprocess.run(["systemctl", "--user", "daemon-reload"], check=True)

apps = home / ".local/share/applications"
apps.mkdir(parents=True, exist_ok=True)
(apps / "redmi-audio.desktop").write_text(f"""[Desktop Entry]
Type=Application
Name=Redmi Audio
Comment=Escuchar el PC en Android y enviar audio al PC
Exec=/usr/bin/python3 {project / 'app.py'}
Terminal=false
Categories=AudioVideo;Audio;
""")
print("Redmi Audio instalado. Abre 'Redmi Audio' desde el menú de aplicaciones.")
