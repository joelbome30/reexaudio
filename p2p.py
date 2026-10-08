#!/usr/bin/env python3
"""Small NetworkManager/Gio bridge; no UI or audio processing."""
from pathlib import Path
import ipaddress
import json
import os
import re
import socket
import subprocess
import sys
import time
from gi.repository import Gio, GLib

STATE = Path(os.environ.get("REEXAUDIO_STATE_DIR", str(Path.home() / ".local/state/redmi-audio")))

def command(*args, check=True):
    return subprocess.run(args, text=True, capture_output=True, check=check, timeout=60,
                          env={**os.environ, "LC_ALL": "C"})

def p2p_device():
    for line in command("nmcli", "-t", "-f", "DEVICE,TYPE", "device", "status").stdout.splitlines():
        device, kind = line.split(":", 1)
        if kind == "wifi-p2p":
            return device
    raise RuntimeError("NetworkManager no detecta Wi‑Fi Direct")


def p2p_address():
    try:
        details = command("iw", "dev").stdout
    except FileNotFoundError:
        details = ""
    match = re.search(r"\baddr\s+([0-9a-f:]{17})\s+type P2P-device\b", details, re.I)
    if match:
        return match.group(1).upper()
    device = p2p_device().removeprefix("p2p-dev-")
    address_file = Path("/sys/class/net") / device / "address"
    if address_file.exists():
        return address_file.read_text().strip().upper()
    raise RuntimeError("No pude leer la dirección Wi‑Fi Direct del PC")


def dbus_property(bus, path, interface, name):
    result = bus.call_sync("org.freedesktop.NetworkManager", path,
                           "org.freedesktop.DBus.Properties", "Get",
                           GLib.Variant("(ss)", (interface, name)), GLib.VariantType("(v)"),
                           Gio.DBusCallFlags.NONE, 5000, None)
    return result.unpack()[0]


def discover_p2p():
    device = p2p_device()
    path = command("nmcli", "-g", "GENERAL.DBUS-PATH", "device", "show", device).stdout.strip()
    bus = Gio.bus_get_sync(Gio.BusType.SYSTEM, None)
    interface = "org.freedesktop.NetworkManager.Device.WifiP2P"
    try:
        bus.call_sync("org.freedesktop.NetworkManager", path, interface, "StartFind",
                      GLib.Variant("(a{sv})", ({"timeout": GLib.Variant("i", 30)},)),
                      None, Gio.DBusCallFlags.NONE, 5000, None)
    except GLib.Error as error:
        if "busy" not in str(error).lower():
            raise
    time.sleep(6)
    peers = dbus_property(bus, path, interface, "Peers")
    found = []
    for peer in peers:
        peer_interface = "org.freedesktop.NetworkManager.WifiP2PPeer"
        name = dbus_property(bus, peer, peer_interface, "Name")
        address = dbus_property(bus, peer, peer_interface, "HwAddress")
        if address:
            found.append((name or "Dispositivo sin nombre", address))
    return found


def connect_p2p(address):
    device = p2p_device()
    name = "redmi-audio-p2p"
    command("nmcli", "connection", "delete", name, check=False)
    try:
        command("nmcli", "connection", "add", "type", "wifi-p2p", "ifname", device,
                "con-name", name, "connection.autoconnect", "no", "wifi-p2p.peer", address,
                "wifi-p2p.wps-method", "pbc",
                "ipv4.method", "auto", "ipv4.never-default", "yes", "ipv6.method", "disabled")
        command("nmcli", "--wait", "20", "connection", "up", name, "ifname", device)
        return confirm_p2p(device)
    except (OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired, RuntimeError) as error:
        command("nmcli", "connection", "delete", name, check=False)
        reason = error.stderr.strip() if isinstance(error, subprocess.CalledProcessError) and error.stderr else str(error)
        raise RuntimeError(f"No se pudo enlazar con el dispositivo Wi‑Fi Direct: {reason}") from error


def confirm_p2p(device):
    gateway = command("nmcli", "-g", "IP4.GATEWAY", "device", "show", device).stdout.strip()
    if not gateway:
        address_text = command("nmcli", "-g", "IP4.ADDRESS", "device", "show", device).stdout.strip()
        if address_text:
            gateway = str(ipaddress.ip_interface(address_text.splitlines()[0]).network.network_address + 1)
    if not gateway:
        raise RuntimeError("Wi‑Fi Direct no obtuvo una dirección IP")
    with socket.create_connection((gateway, 53318), timeout=10) as peer:
        peer.sendall(((STATE / "token").read_text().strip() + "\n").encode())
        peer.settimeout(5)
        if peer.makefile("rb").readline(16).strip() != b"OK":
            raise RuntimeError("El celular no confirmó la conexión")
    return True


def connected_p2p():
    active = command("nmcli", "-t", "-f", "NAME", "connection", "show", "--active").stdout
    return "redmi-audio-p2p" in active.splitlines()


if __name__ == "__main__":
    try:
        action = sys.argv[1]
        if action == "device":
            result = p2p_device()
        elif action == "address":
            result = p2p_address()
        elif action == "discover":
            result = discover_p2p()
        elif action == "connected":
            result = connected_p2p()
        elif action == "connect" and len(sys.argv) == 3:
            if not re.fullmatch(r"[0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5}", sys.argv[2]):
                raise ValueError("Dirección Wi-Fi Direct inválida")
            connect_p2p(sys.argv[2])
            result = True
        else:
            raise ValueError("Uso: p2p.py device|address|discover|connected|connect DIRECCION")
        print(json.dumps(result))
    except Exception as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
