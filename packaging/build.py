#!/usr/bin/env python3
"""Build Linux packages from already compiled, matching release binaries."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile

ROOT = Path(__file__).resolve().parent.parent
VERSION = next(line.strip().split("'")[1] for line in (ROOT / 'android/app/build.gradle').read_text().splitlines()
               if line.strip().startswith('versionName '))
PKG_VERSION = VERSION.replace('-', '_') + '-1'


def copy(source, target, executable=False):
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, target)
    if executable:
        target.chmod(0o755)


def stage(root, binaries, apk):
    for name in ('reexaudio', 'reexaudio-server'):
        copy(binaries / name, root / 'usr/bin' / name, True)
    copy(apk, root / 'usr/lib/reexaudio/app.apk')
    copy(ROOT / 'THIRD_PARTY.md', root / 'usr/share/doc/reexaudio/THIRD_PARTY.md')
    copy(ROOT / 'vendor/Slint-LICENSE.md', root / 'usr/share/doc/reexaudio/Slint-LICENSE.md')
    copy(ROOT / 'vendor/material-1.1.0/LICENSE.md', root / 'usr/share/doc/reexaudio/Material-LICENSE.md')
    copy(ROOT / 'packaging/reexaudio.svg', root / 'usr/share/icons/hicolor/scalable/apps/reexaudio.svg')
    unit = root / 'usr/lib/systemd/user/redmi-audio.service'
    unit.parent.mkdir(parents=True, exist_ok=True)
    unit.write_text('''[Unit]
Description=ReExAudio audio bridge
After=pipewire-pulse.service
StartLimitIntervalSec=30
StartLimitBurst=3

[Service]
Type=notify
ExecStart=/usr/bin/reexaudio-server
Restart=on-failure
RestartSec=2
TimeoutStartSec=30
TimeoutStopSec=15
KillMode=control-group
''')
    desktop = root / 'usr/share/applications/reexaudio.desktop'
    desktop.parent.mkdir(parents=True, exist_ok=True)
    desktop.write_text('''[Desktop Entry]
Type=Application
Name=ReExAudio
Comment=Escuchar el PC en Android y enviar audio al PC
Exec=reexaudio
Icon=reexaudio
Terminal=false
Categories=AudioVideo;Audio;
StartupWMClass=reexaudio
''')


def arch_package(root, output):
    meta = root / '.PKGINFO'
    size = sum(p.stat().st_size for p in root.rglob('*') if p.is_file())
    meta.write_text(f'''pkgname = reexaudio
pkgbase = reexaudio
pkgver = {PKG_VERSION}
pkgdesc = Audio entre Linux y Android por Wi-Fi Direct o red local
url = https://github.com/joelbome30/reexaudio
builddate = {int(__import__('time').time())}
packager = ReExAudio
size = {size}
arch = x86_64
license = custom:ReExAudio
'''+''.join(f'depend = {x}\n' for x in ('glibc', 'fontconfig', 'libxkbcommon', 'libpulse', 'networkmanager', 'iw')))
    with tarfile.open(output.with_suffix(''), 'w') as archive:
        for file in sorted(root.rglob('*')):
            archive.add(file, arcname=file.relative_to(root))
    subprocess.run(['zstd', '-q', '-f', '-6', str(output.with_suffix('')), '-o', str(output)], check=True)
    output.with_suffix('').unlink()
    meta.unlink()


def deb_package(root, output):
    if not shutil.which('dpkg-deb'):
        return False
    control = root / 'DEBIAN/control'
    control.parent.mkdir(parents=True)
    control.write_text(f'''Package: reexaudio
Version: {VERSION.replace('-beta', '~beta')}
Section: sound
Priority: optional
Architecture: amd64
Maintainer: ReExAudio <noreply@github.com>
Depends: libc6, libfontconfig1, libxkbcommon0, network-manager, iw, pulseaudio-utils, pipewire-pulse | pulseaudio
Description: Audio entre Linux y Android
 Envia y reproduce audio local por Wi-Fi Direct o la red local.
''')
    subprocess.run(['dpkg-deb', '--build', '--root-owner-group', str(root), str(output)], check=True)
    return True


def appimage(root, output, tool):
    if tool is None:
        return False
    app = root
    # AppRun installs a stable copy because the systemd audio service outlives the mounted image.
    apprun = app / 'AppRun'
    apprun.write_text('''#!/bin/sh
set -eu
APPDIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
python3 "$APPDIR/usr/lib/reexaudio/install.py" --bin-dir "$APPDIR/usr/bin"
exec "$HOME/.local/lib/reexaudio/reexaudio" "$@"
''')
    apprun.chmod(0o755)
    copy(ROOT / 'install.py', app / 'usr/lib/reexaudio/install.py')
    copy(ROOT / 'THIRD_PARTY.md', app / 'usr/lib/reexaudio/THIRD_PARTY.md')
    copy(ROOT / 'vendor/Slint-LICENSE.md', app / 'usr/lib/reexaudio/vendor/Slint-LICENSE.md')
    copy(ROOT / 'vendor/material-1.1.0/LICENSE.md', app / 'usr/lib/reexaudio/vendor/material-1.1.0/LICENSE.md')
    copy(ROOT / 'packaging/reexaudio.svg', app / 'reexaudio.svg')
    copy(app / 'usr/share/applications/reexaudio.desktop', app / 'reexaudio.desktop')
    # AppImage integration points to AppRun, which installs and starts the persistent service.
    (app / 'reexaudio.desktop').write_text((app / 'reexaudio.desktop').read_text().replace('Exec=reexaudio', 'Exec=AppRun'))
    env = {**os.environ, 'ARCH': 'x86_64', 'APPIMAGE_EXTRACT_AND_RUN': '1'}
    subprocess.run([str(tool), str(app), str(output)], env=env, check=True)
    return True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bin-dir', type=Path, default=ROOT / 'target/release')
    parser.add_argument('--apk', type=Path, default=ROOT / 'android/app/build/outputs/apk/debug/app-debug.apk')
    parser.add_argument('--out', type=Path, default=ROOT / 'dist')
    parser.add_argument('--appimagetool', type=Path)
    args = parser.parse_args()
    for file in [args.bin_dir / 'reexaudio', args.bin_dir / 'reexaudio-server', args.apk]:
        if not file.is_file(): parser.error(f'Falta {file}')
    args.out.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as temp:
        root = Path(temp) / 'root'
        stage(root, args.bin_dir, args.apk)
        arch_package(root, args.out / f'ReExAudio-{VERSION}-x86_64.pkg.tar.zst')
        # GitHub Releases replaces '~' in asset filenames with '.'. Keep the
        # filename stable there while Debian metadata retains the Debian beta suffix.
        if deb_package(root, args.out / f'reexaudio_{VERSION.replace("-beta", ".beta")}_amd64.deb'):
            print('Paquete Debian creado')
        # DEBIAN is metadata for dpkg, and must not enter the AppImage.
        shutil.rmtree(root / 'DEBIAN', ignore_errors=True)
        appimage(root, args.out / f'ReExAudio-{VERSION}-x86_64.AppImage', args.appimagetool)

if __name__ == '__main__': main()
