# ReExAudio

Audio entre un PC Linux y Android: escucha el PC con la pantalla del celular apagada o envía al PC el micrófono o el audio interno del teléfono. Funciona por Wi-Fi Direct (P2P) o por Wi-Fi local, sin servidores externos.

El escritorio usa **Rust + Slint y componentes Material 3**, con temas claro/oscuro y acentos azul, verde y lila. El servidor es un proceso Rust independiente de la ventana. Android sigue siendo una aplicación nativa Java, con tarjetas, temas claro/oscuro y los mismos acentos que el escritorio. Los ajustes de apariencia se guardan en cada dispositivo.

## Uso

1. Instala el APK en Android y abre **ReExAudio** en el PC.
2. Elige una conexión:
   - **Wi-Fi Direct (predeterminado):** abre ReExAudio en Android y escanea el QR del PC. El PC busca y conecta el teléfono automáticamente; acepta la solicitud Wi-Fi Direct en Android. No hace falta router ni zona Wi-Fi.
   - **Wi-Fi local:** conecta ambos a la misma red, elige **Wi-Fi local**, escanea el QR y pulsa **Iniciar audio** en el PC. Si cambias de red, pulsa **Actualizar QR**. En equipos con VPN o varias redes, comprueba que la dirección mostrada sea accesible desde Android.
3. Desde Android elige **Escuchar el PC**, **Enviar audio del celular al PC** o **Enviar micrófono al PC**.

**Escanear QR del PC** abre un escáner en vivo dentro de Android: concede el permiso de cámara y apunta al código. Se reconoce automáticamente, sin tomar ni guardar fotos. También puedes pegar el enlace de emparejamiento.

Si P2P agota el tiempo, pulsa **Reintentar conexión P2P** en Android. Wi-Fi Direct depende del teléfono, el controlador y el adaptador; necesita una prueba real con cada combinación.

**Enviar sonido del PC** permite redirigir las aplicaciones del PC al celular. Desactívalo antes de iniciar si solo quieres recibir audio del teléfono. Puedes elegir la salida física donde escucharlo y ajustar el volumen enviado entre 0 y 150 %. **Actualizar salidas de audio** detecta nuevos dispositivos; al cambiar de salida, reinicia el envío desde Android.

La ventana se puede cerrar sin cortar el audio. **Detener audio** detiene el servicio y restaura la salida anterior del PC. El servicio guarda un registro de la salida: tras una caída abrupta, intenta recuperarla al siguiente arranque. Si esa salida ya no existe, busca otra salida física disponible.

## Perfiles de latencia

| Perfil | Captura del PC | Búfer de reproducción en el PC | Uso |
| --- | --- | --- | --- |
| Rendimiento | 10 ms | 15 ms | Menor retardo, red estable |
| Equilibrado | 20 ms | 40 ms | Perfil inicial |
| Calidad | 40 ms | 80 ms | Mayor tolerancia a fluctuaciones |

Estos valores son objetivos de búfer, **no la latencia total**. Los tres conservan la misma fidelidad PCM. El perfil viaja en el QR; Android lo selecciona al escanearlo y permite cambiarlo antes de iniciar. Si se interrumpe “Escuchar el PC”, Android intenta reconectar hasta cinco veces; para aplicar un cambio de perfil, detén y reinicia el audio.

Android usa un servicio en primer plano para reproducir con la pantalla apagada. La captura de audio interno requiere el permiso del sistema y que la aplicación de origen permita capturar su audio.

## Descargar e instalar

Descarga la versión **0.3.3 beta** desde [GitHub Releases](https://github.com/joelbome30/reexaudio/releases/tag/v0.3.3-beta). El [APK de Android](https://github.com/joelbome30/reexaudio/releases/download/v0.3.3-beta/ReExAudio-0.3.3-beta.apk) está allí mismo.

En Linux x86-64 hay dos formas de instalar:

- **AppImage:** descarga [ReExAudio-0.3.3-beta-x86_64.AppImage](https://github.com/joelbome30/reexaudio/releases/download/v0.3.3-beta/ReExAudio-0.3.3-beta-x86_64.AppImage), dale permiso de ejecución (`chmod +x ReExAudio-*.AppImage`) y ábrelo. El primer arranque registra el servicio de usuario y deja el motor de audio en `~/.local/lib/reexaudio` para que continúe funcionando al cerrar la ventana.
- **Gestor de paquetes:** en Debian/Ubuntu descarga el [paquete .deb](https://github.com/joelbome30/reexaudio/releases/download/v0.3.3-beta/reexaudio_0.3.3~beta_amd64.deb) e instala con `sudo apt install ./reexaudio_0.3.3~beta_amd64.deb`; en Arch/CachyOS descarga el [paquete pacman](https://github.com/joelbome30/reexaudio/releases/download/v0.3.3-beta/ReExAudio-0.3.3-beta-x86_64.pkg.tar.zst) e instala con `sudo pacman -U ReExAudio-0.3.3-beta-x86_64.pkg.tar.zst`. Abre ReExAudio desde el menú de aplicaciones.

Para Wi-Fi Direct necesitas NetworkManager, un adaptador compatible y Python con Gio/PyGObject. Ambas distribuciones usan PipeWire/PulseAudio y `pactl`, `parec`, `pacat`. El AppImage usa las bibliotecas gráficas habituales del sistema y requiere una distribución con glibc 2.35 o posterior. Si actualizas desde la instalación manual anterior, detén el audio, elimina `~/.config/systemd/user/redmi-audio.service` y ejecuta `systemctl --user daemon-reload` antes de abrir el paquete del gestor; esa unidad de usuario tiene prioridad sobre la incluida por el paquete.

## Compilar e instalar en Linux desde el código

Para compilar: **Rust/Cargo 1.92 o posterior**, compilador/enlazador C, `pkg-config` y los archivos de desarrollo de Fontconfig y las bibliotecas de escritorio que requiera Winit (X11/Wayland y xkbcommon). La primera compilación descarga los paquetes fijados en `Cargo.lock` y puede tardar varios minutos. El instalador no descarga ni instala dependencias del sistema.

Para ejecutar: PipeWire con `pipewire-pulse` o PulseAudio, `pactl`, `parec`, `pacat`, systemd de usuario y las bibliotecas gráficas X11/Wayland y Fontconfig. Para P2P: NetworkManager, `nmcli`, `iw`, `wpa_supplicant`, Python 3 con PyGObject/Gio y un adaptador compatible. Python se usa únicamente en el instalador, los lanzadores de compatibilidad y el pequeño puente de NetworkManager; la interfaz y el servidor son Rust. Ya no se necesitan Tkinter ni `qrencode`.

```bash
git clone https://github.com/joelbome30/reexaudio.git
cd reexaudio
cargo build --release --locked -j 2
python3 install.py
```

El instalador copia los binarios y el puente P2P a `~/.local/lib/reexaudio`, crea el acceso del menú y actualiza `redmi-audio.service`. No inicia el audio automáticamente. Si estás actualizando, **detén primero el audio desde la ventana anterior** y cierra esa ventana. El token y los ajustes existentes en `~/.local/state/redmi-audio` se conservan; no hace falta volver a emparejar por cambiar el servidor.

Para una compilación de desarrollo, usa `cargo build --locked -j 2` y `python3 install.py --bin-dir target/debug`. Puedes previsualizar la configuración sin instalar con `python3 install.py --dry-run`. Los lanzadores `python3 app.py` y `python3 server.py` ejecutan los nuevos binarios.

## Compilar Android

Requiere JDK 17 y Android SDK 35. Incluye Gradle Wrapper 8.13.

```bash
cd android
./gradlew assembleDebug
```

El APK queda en `android/app/build/outputs/apk/debug/app-debug.apk`. Si existe al instalar el escritorio, se copia junto al servidor. Con el servidor activo, también se descarga desde el enlace local del QR. Tras compilar un APK nuevo, vuelve a ejecutar el instalador para actualizar esa copia.

## Arquitectura y compatibilidad

- `ui/main.slint`: interfaz Material 3; los componentes originales y su licencia MIT están en `vendor/material-1.1.0`.
- `src/desktop.rs`: controles de la ventana y un trabajador para operaciones de disco, audio, systemd y P2P; no bloquean el hilo gráfico.
- `src/server.rs`: HTTP y WebSocket con Tokio/Axum (que utiliza tokio-tungstenite), límites de sesiones/mensajes, latido de conexión y cierre de los procesos de audio.
- `src/audio.rs`: creación del dispositivo virtual, redirección y recuperación de la salida del PC.
- `src/config.rs`: ajustes y escritura atómica, token y compatibilidad con el estado anterior.
- `p2p.py`: puente acotado con NetworkManager/Gio; conserva el flujo Wi-Fi Direct.
- `android/`: reproducción y captura nativas en segundo plano.

La interfaz se dibuja con Slint/Winit y el renderizador por software, sin navegador ni Qt. El servidor se puede compilar sin Slint con `cargo build --release --locked --no-default-features --bin reexaudio-server`.

Se conservan el puerto **53317**, las rutas `/{token}/listen` y `/{token}/send`, el QR `redmiaudio://p2p/…`, la confirmación P2P en el puerto **53318**, y los tres perfiles. El audio es PCM s16le a 48 kHz, estéreo del PC a Android y mono de Android al PC. `parec` y `pacat` siguen hablando con el servidor de audio del sistema. Una biblioteca implementa WebSocket completo, incluyendo ping/pong, fragmentación y cierre.

El token protege el emparejamiento; el transporte local HTTP/WebSocket no está cifrado. El servidor admite hasta ocho sesiones de audio simultáneas y corta conexiones que dejan de responder o de consumir audio, para evitar acumular audio atrasado indefinidamente. En “Escuchar el PC”, Android recupera cortes breves con hasta cinco reintentos; otros modos deben iniciarse otra vez si se interrumpe la conexión.

Licencias y atribución: [THIRD_PARTY.md](THIRD_PARTY.md).

## Planes futuros

Se planean versiones nativas para **Windows** y **macOS**, conservando el enfoque en audio local y bajo retardo. Aún no hay fecha ni instaladores para esos sistemas.

## Verificación y diagnóstico

```bash
cargo fmt --all -- --check
cargo test --locked --no-default-features -j 2
cargo check --locked --bin reexaudio -j 2
python3 -m py_compile app.py server.py p2p.py install.py
journalctl --user -u redmi-audio.service -n 60
```

Las pruebas del servidor utilizan procesos de audio simulados y puertos efímeros: cubren HTTP, autenticación, APK, PCM, perfiles, ping/pong, cierre y recuperación tras fallos sin redirigir el audio real. La compilación no sustituye la prueba de escucha bidireccional y emparejamiento con un teléfono.

`cargo run --locked --example preview -- /tmp/reexaudio-preview` genera cuatro capturas PPM (oscuro, claro, ventana pequeña y desplazamiento) con datos ficticios, sin abrir ventanas, iniciar el servicio ni acceder al audio.

Para pruebas aisladas se pueden establecer `REEXAUDIO_STATE_DIR` (directorio de estado separado) y `REEXAUDIO_LISTEN` (dirección del servidor; por defecto `0.0.0.0:53317`). El QR de la aplicación utiliza el puerto de compatibilidad 53317. No compartas el token ni el QR en registros públicos.
