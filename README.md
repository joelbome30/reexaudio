# ReExAudio

Reproduce el sonido de un PC Linux en Android, incluso con la pantalla apagada. También puede enviar al PC el micrófono o el audio interno del celular. La conexión usa audio PCM y WebSocket en la red local.

## Conexiones

- **P2P (predeterminado):** Wi‑Fi Direct entre PC y Android, sin router ni zona Wi‑Fi. La app Android crea el grupo y el PC lo encuentra mediante NetworkManager.
- **Red local:** PC y Android conectados a la misma Wi‑Fi. El QR contiene la dirección local del PC.

No se usa ningún servidor externo. El QR contiene un token de emparejamiento aleatorio que protege las rutas de audio.

## Uso

1. Instala el APK en Android.
2. En el PC abre **ReExAudio**. En P2P el audio se inicia al enlazar el celular; en red local pulsa **Iniciar**.
3. Elige el tipo de conexión. En Android pulsa **Escanear QR del PC** y toma una foto del código. En P2P, deja abierta la app del celular, pulsa **Buscar celular** en el PC, selecciona el dispositivo y pulsa **Conectar P2P**. Acepta la conexión Wi‑Fi Direct en Android si aparece la solicitud. Si se agota el tiempo, pulsa **Reintentar conexión P2P** en Android y repite la búsqueda en el PC. En red local, conecta ambos a la misma Wi‑Fi y fotografía el QR.
4. En Android pulsa **Escuchar el PC**, **Enviar audio del celular al PC** o **Enviar micrófono al PC**.

## Perfiles de retardo

- **Rendimiento:** búfer pequeño para reducir el retardo; necesita una conexión estable.
- **Equilibrado:** retardo y tolerancia a cortes intermedios; es el perfil inicial.
- **Calidad:** búfer mayor para evitar cortes cuando la conexión fluctúa. El audio PCM conserva la misma fidelidad en los tres perfiles.

El perfil elegido en el PC viaja en el QR y aparece seleccionado en Android al escanearlo. Puedes cambiarlo en Android antes de iniciar el audio. Para aplicar un cambio mientras escuchas, detén el audio y vuelve a iniciarlo.

La app Android usa un servicio de reproducción en primer plano para seguir sonando cuando la pantalla se apaga. El audio interno solo se puede capturar tras aceptar el permiso de Android y si la app que lo reproduce permite la captura; algunas apps lo bloquean.

## Instalar la app del PC

Requiere Linux con PipeWire o PulseAudio, `pactl`, `parec`, `pacat`, NetworkManager, `qrencode`, Python 3, Tkinter y PyGObject. Wi‑Fi Direct también requiere `iw`, `wpa_supplicant` y un adaptador compatible.

```bash
git clone https://github.com/joelbome30/reexaudio.git
cd reexaudio
python3 install.py
```

El instalador crea un servicio de usuario y un acceso en el menú de aplicaciones. **Iniciar** envía la salida del PC a un dispositivo virtual y activa el servidor; **Detener** devuelve el sonido al dispositivo anterior.

## Compilar el APK

Requiere JDK 17 y Android SDK 35. El proyecto incluye Gradle Wrapper 8.13.

```bash
cd android
./gradlew assembleDebug
```

El APK queda en `android/app/build/outputs/apk/debug/app-debug.apk`. Cuando el servidor del PC está activo, también se puede descargar desde la página local que muestra el QR del modo **Red local**.

## Código

- `app.py`: ventana del PC, QR, ajustes y conexión Wi‑Fi Direct.
- `server.py`: transmisión bidireccional de audio.
- `android/`: app Android con reproducción y captura en segundo plano.

La conexión directa necesita probarse en cada combinación de teléfono y adaptador Wi‑Fi; algunos fabricantes cambian el comportamiento de Wi‑Fi Direct.
