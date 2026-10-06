#!/usr/bin/env python3
"""Private LAN audio bridge between PipeWire and the Android companion app."""

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import base64
import hashlib
import json
import re
import socket
import struct
import subprocess


STATE = Path.home() / ".local/state/redmi-audio"
TOKEN = (STATE / "token").read_text().strip()
CONFIG = STATE / "config.json"
APK = Path(__file__).resolve().parent / "android/app/build/outputs/apk/debug/app-debug.apk"
BUILD_FILE = Path(__file__).resolve().parent / "android/app/build.gradle"
APK_VERSION = re.search(r"versionName\s+'([^']+)'", BUILD_FILE.read_text()).group(1)
APK_NAME = f"ReExAudio-{APK_VERSION}.apk"
PORT = 53317
GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"


def reverse_sink():
    try:
        return json.loads(CONFIG.read_text()).get("reverse_sink", "")
    except (OSError, ValueError):
        return ""


def frame(payload):
    length = len(payload)
    if length < 126:
        return bytes((0x82, length)) + payload
    if length < 65536:
        return b"\x82\x7e" + struct.pack("!H", length) + payload
    return b"\x82\x7f" + struct.pack("!Q", length) + payload


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass

    def do_GET(self):
        path = self.path.split("?", 1)[0]
        if path == f"/{TOKEN}":
            body = ("<!doctype html><html lang='es'><meta name='viewport' "
                    "content='width=device-width, initial-scale=1'><title>ReExAudio</title>"
                    "<style>body{font:20px system-ui;max-width:35rem;margin:4rem auto;padding:1rem;"
                    "background:#17191d;color:white}a{color:#61d095}</style>"
                    "<h1>ReExAudio</h1><p>Instala la aplicación y escanea el QR que aparece "
                    "en el PC. La aplicación mantiene el sonido al apagar la pantalla.</p>"
                    + (f"<p><a href='/{TOKEN}/{APK_NAME}'>Descargar {APK_NAME}</a></p>" if APK.exists() else "")
                    + "</html>").encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return

        if path in (f"/{TOKEN}/app.apk", f"/{TOKEN}/{APK_NAME}") and APK.exists():
            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.android.package-archive")
            self.send_header("Content-Disposition", f"attachment; filename={APK_NAME}")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(APK.stat().st_size))
            self.end_headers()
            with APK.open("rb") as file:
                while chunk := file.read(65536):
                    self.wfile.write(chunk)
            return

        if path not in (f"/{TOKEN}/listen", f"/{TOKEN}/send"):
            self.send_error(404)
            return
        if self.headers.get("Upgrade", "").lower() != "websocket":
            self.send_error(400)
            return
        key = self.headers.get("Sec-WebSocket-Key", "")
        if not key:
            self.send_error(400)
            return
        accept = base64.b64encode(hashlib.sha1((key + GUID).encode()).digest()).decode()
        self.send_response(101, "Switching Protocols")
        self.send_header("Upgrade", "websocket")
        self.send_header("Connection", "Upgrade")
        self.send_header("Sec-WebSocket-Accept", accept)
        self.end_headers()

        if path.endswith("/listen"):
            self.listen_to_pc()
        else:
            self.send_to_pc()

    def listen_to_pc(self):
        command = ["parec", "--device=redmi_phone.monitor", "--format=s16le",
                   "--rate=48000", "--channels=2", "--latency-msec=20", "--raw"]
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        try:
            while chunk := process.stdout.read(8192):
                self.connection.sendall(frame(chunk))
        except (BrokenPipeError, ConnectionResetError, OSError):
            pass
        finally:
            process.terminate()
            process.wait()

    def read_exact(self, size):
        data = self.rfile.read(size)
        if len(data) != size:
            raise EOFError
        return data

    def receive_frame(self):
        first, second = self.read_exact(2)
        opcode = first & 15
        length = second & 127
        if length == 126:
            length = struct.unpack("!H", self.read_exact(2))[0]
        elif length == 127:
            length = struct.unpack("!Q", self.read_exact(8))[0]
        if length > 262144 or not second & 128:
            raise ValueError("Invalid client frame")
        mask = self.read_exact(4)
        payload = bytearray(self.read_exact(length))
        for index in range(length):
            payload[index] ^= mask[index & 3]
        return opcode, payload

    def send_to_pc(self):
        sink = reverse_sink()
        if not sink:
            return
        command = ["pacat", "--playback", "--device=" + sink, "--format=s16le",
                   "--rate=48000", "--channels=1", "--latency-msec=40", "--raw"]
        process = subprocess.Popen(command, stdin=subprocess.PIPE, stderr=subprocess.DEVNULL)
        try:
            while True:
                opcode, payload = self.receive_frame()
                if opcode == 8:
                    break
                if opcode == 2:
                    process.stdin.write(payload)
                    process.stdin.flush()
        except (EOFError, BrokenPipeError, ConnectionResetError, OSError, ValueError):
            pass
        finally:
            process.terminate()
            process.wait()


ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
