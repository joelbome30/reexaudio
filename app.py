#!/usr/bin/env python3
"""Compatibility launcher for the Rust desktop; no Python UI remains."""
import os
from pathlib import Path


def launch(name):
    project = Path(__file__).resolve().parent
    candidates = [project / "target/release" / name,
                  Path.home() / ".local/lib/reexaudio" / name,
                  project / "target/debug" / name]
    for path in candidates:
        if path.is_file():
            os.execv(str(path), [str(path)])
    raise SystemExit("Compila con cargo build --release --locked -j 2 y ejecuta python3 install.py.")


if __name__ == "__main__":
    launch("reexaudio")
