#!/usr/bin/env python3
"""Desktop controls and QR pairing for ReExAudio."""

from pathlib import Path
import ipaddress
import json
import re
import socket
import subprocess
import threading
import time
import tkinter as tk
from tkinter import ttk, messagebox
import gi
from gi.repository import Gio, GLib


STATE = Path.home() / ".local/state/redmi-audio"
CONFIG = STATE / "config.json"
TOKEN = (STATE / "token").read_text().strip()
SERVICE = "redmi-audio.service"
VIRTUAL = "redmi_phone"
VERSION = re.search(r"versionName\s+'([^']+)'", (Path(__file__).resolve().parent /
                    "android/app/build.gradle").read_text()).group(1)


def command(*args, check=True):
    return subprocess.run(args, text=True, capture_output=True, check=check)


def pactl(*args):
    return command("pactl", *args).stdout.strip()


def sinks():
    return [line.split("\t")[1] for line in pactl("list", "short", "sinks").splitlines()]


def current_sink():
    for line in pactl("info").splitlines():
        if line.startswith("Default Sink:"):
            return line.split(":", 1)[1].strip()
    return ""


def ip_address():
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        sock.connect(("1.1.1.1", 80))
        return sock.getsockname()[0]


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
    bus.call_sync("org.freedesktop.NetworkManager", path, interface, "StartFind",
                  GLib.Variant("(a{sv})", ({"timeout": GLib.Variant("i", 30)},)),
                  None, Gio.DBusCallFlags.NONE, 5000, None)
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
    command("nmcli", "connection", "add", "type", "wifi-p2p", "ifname", device,
            "con-name", name, "connection.autoconnect", "no", "wifi-p2p.peer", address,
            "wifi-p2p.wps-method", "pbc",
            "ipv4.method", "auto", "ipv4.never-default", "yes", "ipv6.method", "disabled")
    try:
        command("nmcli", "--wait", "50", "connection", "up", name, "ifname", device)
    except subprocess.CalledProcessError as error:
        command("nmcli", "connection", "delete", name, check=False)
        raise RuntimeError("Wi‑Fi Direct agotó el tiempo. Abre ReExAudio en el celular, "
                           "escanea el QR nuevo y acepta la solicitud de conexión.") from error
    gateway = command("nmcli", "-g", "IP4.GATEWAY", "device", "show", device).stdout.strip()
    if not gateway:
        address_text = command("nmcli", "-g", "IP4.ADDRESS", "device", "show", device).stdout.strip()
        if address_text:
            gateway = str(ipaddress.ip_interface(address_text.splitlines()[0]).network.network_address + 1)
    if not gateway:
        gateway = "192.168.49.1"
    with socket.create_connection((gateway, 53318), timeout=10) as peer:
        peer.sendall((TOKEN + "\n").encode())
        peer.settimeout(5)
        if peer.recv(16).strip() != b"OK":
            raise RuntimeError("El celular no confirmó la conexión")


def read_config():
    try:
        return json.loads(CONFIG.read_text())
    except (OSError, ValueError):
        return {}


class App:
    def __init__(self):
        STATE.mkdir(parents=True, exist_ok=True)
        self.root = tk.Tk()
        self.root.title(f"ReExAudio {VERSION}")
        self.root.geometry("560x700")
        self.root.minsize(480, 500)
        self.root.configure(bg="#17191d")
        self.cfg = read_config()
        self.connection = tk.StringVar(value=self.cfg.get("connection", "direct"))
        self.profile = tk.StringVar(value=self.cfg.get("profile", "balanced"))
        self.connecting = False
        self.url = ""
        self.build()
        self.update_qr()
        self.refresh()

    def build(self):
        style = ttk.Style()
        style.theme_use("clam")
        style.configure("TFrame", background="#17191d")
        style.configure("TLabel", background="#17191d", foreground="#f2f4f5", font=("Sans", 11))
        style.configure("Title.TLabel", font=("Sans", 22, "bold"))
        style.configure("TButton", font=("Sans", 11), padding=8)
        style.configure("TCheckbutton", background="#17191d", foreground="#f2f4f5", font=("Sans", 11))
        style.configure("TRadiobutton", background="#17191d", foreground="#f2f4f5", font=("Sans", 11))

        scroller = ttk.Frame(self.root)
        scroller.pack(fill="both", expand=True)
        canvas = tk.Canvas(scroller, background="#17191d", highlightthickness=0)
        scrollbar = ttk.Scrollbar(scroller, orient="vertical", command=canvas.yview)
        canvas.configure(yscrollcommand=scrollbar.set)
        canvas.pack(side="left", fill="both", expand=True)
        scrollbar.pack(side="right", fill="y")
        body = ttk.Frame(canvas, padding=24)
        body_window = canvas.create_window((0, 0), window=body, anchor="nw")
        body.bind("<Configure>", lambda _: canvas.configure(scrollregion=canvas.bbox("all")))
        canvas.bind("<Configure>", lambda event: canvas.itemconfigure(body_window, width=event.width))
        canvas.bind("<Button-4>", lambda _: canvas.yview_scroll(-3, "units"))
        canvas.bind("<Button-5>", lambda _: canvas.yview_scroll(3, "units"))
        ttk.Label(body, text=f"ReExAudio {VERSION}", style="Title.TLabel").pack(anchor="w")
        ttk.Label(body, text="Tipo de conexión").pack(anchor="w", pady=(14, 2))
        ttk.Radiobutton(body, text="P2P: Wi‑Fi Direct, sin router (predeterminado)",
                        variable=self.connection, value="direct", command=self.connection_changed).pack(anchor="w")
        ttk.Radiobutton(body, text="Red local: ambos en la misma Wi‑Fi",
                        variable=self.connection, value="local", command=self.connection_changed).pack(anchor="w")
        ttk.Label(body, text="Perfil de retardo").pack(anchor="w", pady=(10, 2))
        for value, label in (("performance", "Rendimiento · menos retardo"),
                             ("balanced", "Equilibrado"),
                             ("quality", "Calidad · más estabilidad")):
            ttk.Radiobutton(body, text=label, variable=self.profile, value=value,
                            command=self.profile_changed).pack(anchor="w")
        self.network_note = ttk.Label(body, text="", wraplength=500)
        self.network_note.pack(anchor="w", pady=(6, 8))
        ttk.Label(body, text="Escanea este QR desde la app del celular.").pack(anchor="w", pady=(0, 8))

        self.qr_label = ttk.Label(body)
        self.qr_label.pack(anchor="center")

        self.p2p_controls = ttk.Frame(body)
        self.p2p_controls.pack(fill="x", pady=(6, 4))
        ttk.Button(self.p2p_controls, text="Buscar celular", command=self.find_phone).pack(side="left")
        self.peer_choice = tk.StringVar()
        self.peer_combo = ttk.Combobox(self.p2p_controls, textvariable=self.peer_choice, state="readonly", width=19)
        self.peer_combo.pack(side="left", padx=6)
        self.connect_button = ttk.Button(self.p2p_controls, text="Conectar P2P",
                                         command=self.connect_phone)
        self.connect_button.pack(side="left")
        self.peer_map = {}
        self.p2p_status = ttk.Label(body, text="")
        self.p2p_status.pack(anchor="center")

        self.status = ttk.Label(body, text="")
        self.status.pack(anchor="center", pady=(8, 4))
        ttk.Button(body, text="Copiar enlace", command=self.copy_link).pack(anchor="center", pady=(0, 16))
        ttk.Button(body, text="Actualizar QR después de cambiar de red", command=self.update_qr).pack(anchor="center", pady=(0, 12))

        self.pc_audio = tk.BooleanVar(value=self.cfg.get("pc_audio", True))
        ttk.Checkbutton(body, text="Enviar audio del PC al celular", variable=self.pc_audio,
                        command=self.save).pack(anchor="w", pady=(0, 10))
        ttk.Label(body, text="Volumen enviado al celular").pack(anchor="w")
        self.volume = tk.IntVar(value=self.cfg.get("volume", 100))
        ttk.Scale(body, from_=0, to=150, variable=self.volume,
                  command=lambda _: self.change_volume()).pack(fill="x", pady=(2, 18))

        ttk.Label(body, text="El audio del celular suena en:").pack(anchor="w")
        physical = [sink for sink in sinks() if sink != VIRTUAL]
        self.reverse_sink = tk.StringVar(value=self.cfg.get("reverse_sink") if self.cfg.get("reverse_sink") in physical
                                          else (physical[0] if physical else ""))
        self.output = ttk.Combobox(body, values=physical, textvariable=self.reverse_sink, state="readonly")
        self.output.pack(fill="x", pady=(4, 18))
        self.output.bind("<<ComboboxSelected>>", lambda _: self.save())

        self.button = ttk.Button(body, text="Iniciar", command=self.toggle)
        self.button.pack(fill="x")
        ttk.Label(body, text="Puedes cerrar esta ventana; el audio seguirá funcionando.",
                  wraplength=460).pack(anchor="w", pady=(18, 0))

    def save(self):
        self.cfg.update(reverse_sink=self.reverse_sink.get(), pc_audio=self.pc_audio.get(),
                        volume=self.volume.get(), connection=self.connection.get(),
                        profile=self.profile.get())
        CONFIG.write_text(json.dumps(self.cfg, indent=2))

    def connection_changed(self):
        self.save()
        self.update_qr()

    def profile_changed(self):
        self.save()
        self.update_qr()

    def update_qr(self):
        base = (f"redmiaudio://p2p/{TOKEN}" if self.connection.get() == "direct"
                else f"http://{ip_address()}:53317/{TOKEN}")
        self.url = f"{base}?profile={self.profile.get()}"
        if self.connection.get() == "direct":
            try:
                self.url += f"&peer={p2p_address()}"
            except RuntimeError as error:
                self.qr_label.configure(image="")
                self.network_note.configure(text=str(error))
                return
        qr_file = STATE / "pairing.png"
        command("qrencode", "-o", str(qr_file), "-s", "5", "-m", "2", self.url)
        self.qr = tk.PhotoImage(file=qr_file)
        self.qr_label.configure(image=self.qr)
        self.network_note.configure(text=(
            "Escanea el QR en Android; después busca el celular aquí y acepta la conexión Wi‑Fi Direct."
            if self.connection.get() == "direct" else
            "Conecta el PC y el celular a la misma Wi‑Fi. Después pulsa «Actualizar QR»."))
        if self.connection.get() == "direct":
            self.p2p_controls.pack(fill="x", pady=(6, 4), before=self.p2p_status)
            self.p2p_status.pack(anchor="center", before=self.status)
        else:
            self.p2p_controls.pack_forget()
            self.p2p_status.pack_forget()

    def find_phone(self):
        self.p2p_status.configure(text="Buscando dispositivos Wi‑Fi Direct…")
        def task():
            try:
                peers = discover_p2p()
                def done():
                    self.peer_map = {f"{name} ({address})": address for name, address in peers}
                    self.peer_combo.configure(values=list(self.peer_map))
                    if peers:
                        self.peer_combo.current(0)
                    self.p2p_status.configure(text=f"{len(peers)} dispositivo(s) encontrado(s)")
                self.root.after(0, done)
            except Exception as error:
                self.root.after(0, lambda msg=str(error): self.p2p_status.configure(text=msg))
        threading.Thread(target=task, daemon=True).start()

    def connect_phone(self):
        if self.connecting:
            return
        address = self.peer_map.get(self.peer_choice.get())
        if not address:
            self.p2p_status.configure(text="Busca y selecciona el celular primero")
            return
        self.connecting = True
        self.connect_button.configure(state="disabled")
        self.p2p_status.configure(text="Conectando por Wi‑Fi Direct…")
        def finish(message):
            self.connecting = False
            self.connect_button.configure(state="normal")
            self.p2p_status.configure(text=message)
        def connected():
            try:
                self.start()
                self.refresh()
                finish("Celular enlazado directamente; audio listo")
            except subprocess.CalledProcessError as error:
                finish("Enlace P2P listo, pero falló el audio: " +
                       (error.stderr or str(error)))
        def task():
            try:
                connect_p2p(address)
                self.root.after(0, connected)
            except Exception as error:
                self.root.after(0, lambda msg=str(error): finish(msg))
        threading.Thread(target=task, daemon=True).start()

    def copy_link(self):
        self.root.clipboard_clear()
        self.root.clipboard_append(self.url)

    def change_volume(self):
        self.save()
        if VIRTUAL in sinks():
            command("pactl", "set-sink-volume", VIRTUAL, f"{self.volume.get()}%", check=False)

    def active(self):
        return command("systemctl", "--user", "is-active", SERVICE, check=False).stdout.strip() == "active"

    def refresh(self):
        active = self.active()
        self.button.configure(text="Detener" if active else "Iniciar")
        self.status.configure(text="● Transmitiendo" if active else "○ Detenido")

    def toggle(self):
        try:
            if self.active():
                self.stop()
            else:
                self.start()
            self.refresh()
        except subprocess.CalledProcessError as error:
            messagebox.showerror("ReExAudio", error.stderr or str(error))

    def start(self):
        self.save()
        if VIRTUAL not in sinks():
            pactl("load-module", "module-null-sink", f"sink_name={VIRTUAL}",
                  "sink_properties=device.description=Redmi_Phone")
        old = current_sink()
        if old != VIRTUAL:
            self.cfg["previous_sink"] = old
            self.save()
        if self.pc_audio.get():
            pactl("set-default-sink", VIRTUAL)
            for line in pactl("list", "short", "sink-inputs").splitlines():
                stream_id = line.split("\t", 1)[0]
                command("pactl", "move-sink-input", stream_id, VIRTUAL, check=False)
        self.change_volume()
        command("systemctl", "--user", "start", SERVICE)

    def stop(self):
        command("systemctl", "--user", "stop", SERVICE)
        previous = self.cfg.get("previous_sink")
        if previous in sinks():
            pactl("set-default-sink", previous)
            for line in pactl("list", "short", "sink-inputs").splitlines():
                columns = line.split("\t")
                if len(columns) > 1 and columns[1] == next((s.split("\t")[0] for s in
                            pactl("list", "short", "sinks").splitlines() if "\t" + VIRTUAL + "\t" in s), ""):
                    command("pactl", "move-sink-input", columns[0], previous, check=False)

    def run(self):
        self.root.after(2000, self.poll_status)
        self.root.mainloop()

    def poll_status(self):
        self.refresh()
        self.root.after(2000, self.poll_status)


if __name__ == "__main__":
    App().run()
