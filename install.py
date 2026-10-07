#!/usr/bin/env python3
"""Install already-built Rust binaries and a per-user service (no root required)."""
from pathlib import Path
import argparse
import filecmp
import shutil
import subprocess


def quoted(value, desktop=False):
    value = str(value).replace("%", "%%").replace("\\", "\\\\").replace('"', '\\"')
    if desktop:
        value = value.replace("$", "\\$").replace("`", "\\`")
    return '"' + value + '"'


def same(source, target):
    return source.is_file() and target.is_file() and filecmp.cmp(source, target, shallow=False)


def main():
    project = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bin-dir", type=Path, default=project / "target/release")
    parser.add_argument("--dry-run", action="store_true", help="Show configuration without changing files or services")
    args = parser.parse_args()
    destination = Path.home() / ".local/lib/reexaudio"
    unit = f"""[Unit]
Description=ReExAudio audio bridge
After=pipewire-pulse.service
StartLimitIntervalSec=30
StartLimitBurst=3

[Service]
Type=notify
ExecStart={quoted(destination / 'reexaudio-server')}
Restart=on-failure
RestartSec=2
TimeoutStartSec=30
TimeoutStopSec=15
KillMode=control-group

[Install]
WantedBy=default.target
"""
    desktop = f"""[Desktop Entry]
Type=Application
Name=ReExAudio
Comment=Escuchar el PC en Android y enviar audio al PC
Exec={quoted(destination / 'reexaudio', desktop=True)}
Icon=audio-headphones
Terminal=false
Categories=AudioVideo;Audio;
StartupWMClass=reexaudio
"""
    if args.dry_run:
        print(unit + "\n" + desktop)
        return
    binaries = [args.bin_dir / name for name in ("reexaudio", "reexaudio-server")]
    if any(not path.is_file() for path in binaries):
        parser.error("Faltan los binarios. Ejecuta cargo build --release --locked -j 2 primero.")
    targets = [destination / source.name for source in binaries]
    apk = next((file for file in (project / "app.apk", project / "android/app/build/outputs/apk/debug/app-debug.apk") if file.exists()), None)
    unit_file = Path.home() / ".config/systemd/user/redmi-audio.service"
    apk_unchanged = same(apk, destination / "app.apk") if apk else not (destination / "app.apk").exists()
    if (all(same(source, target) for source, target in zip(binaries, targets))
            and unit_file.is_file() and unit_file.read_text() == unit
            and same(project / "p2p.py", destination / "p2p.py") and apk_unchanged):
        return
    if subprocess.run(["systemctl", "--user", "is-active", "--quiet", "redmi-audio.service"]).returncode == 0:
        parser.error("Detén el audio desde ReExAudio antes de actualizar el servicio.")
    destination.mkdir(parents=True, exist_ok=True)
    for source in binaries:
        # Replace atomically; never truncate a binary that is already running.
        target = destination / source.name
        staging = target.with_suffix(".new")
        shutil.copy2(source, staging)
        staging.chmod(0o755)
        staging.replace(target)
    shutil.copy2(project / "p2p.py", destination / "p2p.py")
    shutil.copy2(project / "THIRD_PARTY.md", destination / "THIRD_PARTY.md")
    shutil.copy2(project / "vendor/material-1.1.0/LICENSE.md", destination / "Material-LICENSE.md")
    shutil.copy2(project / "vendor/Slint-LICENSE.md", destination / "Slint-LICENSE.md")
    if apk is not None:
        shutil.copy2(apk, destination / "app.apk")
    elif (destination / "app.apk").exists():
        (destination / "app.apk").unlink()
    units = Path.home() / ".config/systemd/user"
    units.mkdir(parents=True, exist_ok=True)
    (units / "redmi-audio.service").write_text(unit)
    apps = Path.home() / ".local/share/applications"
    apps.mkdir(parents=True, exist_ok=True)
    (apps / "redmi-audio.desktop").write_text(desktop)
    subprocess.run(["systemctl", "--user", "daemon-reload"], check=True)
    print("ReExAudio instalado. Abre ReExAudio desde el menú de aplicaciones.")


if __name__ == "__main__":
    main()
