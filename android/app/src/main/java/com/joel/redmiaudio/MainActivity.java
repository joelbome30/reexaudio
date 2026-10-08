package com.joel.redmiaudio;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.widget.AdapterView;
import android.widget.Switch;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

public class MainActivity extends Activity {
    private static final int REQUEST_RECORD = 100;
    private static final int REQUEST_PROJECTION = 101;
    private static final int REQUEST_P2P = 102;
    private static final int REQUEST_QR_SCAN = 103;
    private String pairing;
    private String p2pToken;
    private String p2pStatus = "";
    private String pendingAction;
    private AppStyle style;
    private ScrollView scroller;
    private Button retryButton;
    private TextView connectionLabel;
    private TextView status;
    private Spinner bufferSpinner;
    private SeekBar volumeBar;
    private volatile ServerSocket p2pServer;
    private int p2pGeneration;
    private final Handler audioHandler = new Handler(Looper.getMainLooper());
    private boolean audioRequested;

    @Override public void onCreate(Bundle state) {
        style = new AppStyle(this);
        style.apply();
        super.onCreate(state);
        pairing = getPreferences(MODE_PRIVATE).getString("pairing", "");
        p2pToken = getPreferences(MODE_PRIVATE).getString("p2pToken", "");
        if (!p2pToken.isEmpty()) pairing = "";

        buildInterface();
        if (!p2pToken.isEmpty()) startP2p();
    }

    private void buildInterface() {
        int scrollPosition = scroller == null ? 0 : scroller.getScrollY();
        style = new AppStyle(this);
        style.apply();
        LinearLayout layout = style.column();
        layout.setPadding(dp(20), dp(24), dp(20), dp(28));
        layout.setBackgroundColor(style.background);
        scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        scroller.setClipToPadding(false);
        scroller.addView(layout);
        setContentView(scroller);

        layout.addView(style.title("ReExAudio", 32));
        TextView subtitle = style.label("Tu audio, de un dispositivo a otro", 15, true);
        subtitle.setPadding(0, dp(4), 0, dp(20));
        layout.addView(subtitle);
        status = style.label("", 15, false);
        status.setTextColor(style.onContainer);
        status.setPadding(dp(18), dp(16), dp(18), dp(16));
        status.setBackground(style.shape(style.container, 18));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        layout.addView(status, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout connection = style.card(layout, "Conectar al PC",
                "Escanea el QR de la ventana de ReExAudio para enlazar tus dispositivos.");
        connectionLabel = style.label("", 13, true);
        connection.addView(connectionLabel);
        style.addButton(connection, "Escanear QR del PC", true, this::openQrCamera);
        style.addButton(connection, "Pegar enlace o código", false, this::pastePairing);
        retryButton = style.addButton(connection, "Reintentar Wi-Fi Direct", false, this::startP2p);

        LinearLayout audio = style.card(layout, "Audio", "Elige dónde quieres escuchar.");
        style.addButton(audio, "Escuchar el PC", true, () -> begin(AudioService.PLAY));
        TextView listeningHint = style.label("Sigue escuchando con la pantalla apagada.", 13, true);
        listeningHint.setPadding(dp(4), dp(8), dp(4), dp(6));
        audio.addView(listeningHint);
        style.addButton(audio, "Enviar audio del celular al PC", false, () -> begin(AudioService.SEND_INTERNAL));
        style.addButton(audio, "Enviar micrófono al PC", false, () -> begin(AudioService.SEND_MIC));
        Button stop = style.addButton(audio, "Detener audio", false, () -> {
            audioRequested = false;
            audioHandler.removeCallbacksAndMessages(null);
            startService(new Intent(this, AudioService.class).setAction(AudioService.STOP));
            showStatus("Audio detenido");
        });
        stop.setTextColor(style.primary);
        stop.setBackgroundTintList(ColorStateList.valueOf(style.surface));

        LinearLayout settings = style.card(layout, "Ajustes de audio", "Ajusta el retardo y el volumen a tu gusto.");
        settings.addView(style.title("Perfil de latencia", 16));
        bufferSpinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Rendimiento", "Equilibrado", "Calidad"}) {
            private View color(View view) {
                TextView label = (TextView)view;
                label.setTextColor(style.text);
                label.setBackgroundColor(style.surface);
                label.setPadding(dp(12), dp(14), dp(12), dp(14));
                return label;
            }
            @Override public View getView(int position, View old, ViewGroup parent) {
                return color(super.getView(position, old, parent));
            }
            @Override public View getDropDownView(int position, View old, ViewGroup parent) {
                return color(super.getDropDownView(position, old, parent));
            }
        };
        bufferSpinner.setAdapter(adapter);
        bufferSpinner.setContentDescription("Perfil de latencia");
        bufferSpinner.setBackgroundTintList(ColorStateList.valueOf(style.primary));
        bufferSpinner.setSelection(getPreferences(MODE_PRIVATE).getInt("buffer", 1));
        settings.addView(bufferSpinner, new LinearLayout.LayoutParams(-1, -2));
        TextView profileHint = style.label("", 13, true);
        settings.addView(profileHint);
        bufferSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                getPreferences(MODE_PRIVATE).edit().putInt("buffer", position).apply();
                profileHint.setText(new String[]{"Menos retardo. Requiere una conexión estable.",
                        "Equilibrio entre retardo y tolerancia a cortes.",
                        "Más búfer para tolerar variaciones de la red."}[position]
                        + " Se aplica al iniciar el audio.");
            }
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        TextView volumeLabel = style.title("Volumen en el celular", 16);
        volumeLabel.setPadding(0, dp(22), 0, dp(4));
        settings.addView(volumeLabel);
        TextView volumeValue = style.label("", 14, true);
        settings.addView(volumeValue);
        volumeBar = new SeekBar(this);
        volumeBar.setMax(100);
        volumeBar.setProgress(getPreferences(MODE_PRIVATE).getInt("volume", 100));
        volumeBar.setContentDescription("Volumen en el celular");
        volumeBar.setThumbTintList(ColorStateList.valueOf(style.primary));
        volumeBar.setProgressTintList(ColorStateList.valueOf(style.primary));
        volumeBar.setProgressBackgroundTintList(ColorStateList.valueOf(style.container));
        volumeValue.setText(volumeBar.getProgress() + " %");
        settings.addView(volumeBar, new LinearLayout.LayoutParams(-1, dp(48)));
        volumeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int progress, boolean user) {
                volumeValue.setText(progress + " %");
                if (user) {
                    getPreferences(MODE_PRIVATE).edit().putInt("volume", progress).apply();
                    AudioService.setVolume(progress / 100f);
                }
            }
            public void onStartTrackingTouch(SeekBar bar) {}
            public void onStopTrackingTouch(SeekBar bar) {}
        });

        LinearLayout appearance = style.card(layout, "Apariencia", "Los mismos acentos que en tu PC.");
        Switch theme = new Switch(this);
        theme.setText("Tema oscuro");
        theme.setTextColor(style.text);
        theme.setTextSize(16);
        theme.setMinHeight(dp(48));
        theme.setChecked(style.dark);
        theme.setThumbTintList(ColorStateList.valueOf(style.primary));
        theme.setTrackTintList(ColorStateList.valueOf(style.container));
        appearance.addView(theme, new LinearLayout.LayoutParams(-1, -2));
        theme.setOnCheckedChangeListener((button, checked) -> {
            getSharedPreferences("appearance", MODE_PRIVATE).edit().putBoolean("dark", checked).apply();
            buildInterface();
        });
        LinearLayout accents = new LinearLayout(this);
        String[] names = {"Azul", "Verde", "Lila"};
        for (int i = 0; i < names.length; i++) {
            final int selected = i;
            boolean active = style.accentIndex == i;
            Button button = style.button(names[i], active, () -> {
                getSharedPreferences("appearance", MODE_PRIVATE).edit().putInt("accent", selected).apply();
                buildInterface();
            });
            button.setContentDescription("Acento " + names[i] + (active ? ", seleccionado" : ""));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
            params.setMarginEnd(dp(i < 2 ? 6 : 0));
            params.topMargin = dp(12);
            accents.addView(button, params);
        }
        appearance.addView(accents);
        String version = "";
        try { version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (PackageManager.NameNotFoundException ignored) {}
        TextView footer = style.label("ReExAudio " + version + " · Linux + Android", 12, true);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(24), 0, 0);
        layout.addView(footer);
        updateStatus();
        scroller.post(() -> scroller.scrollTo(0, scrollPosition));
    }

    private int dp(int value) { return style.dp(value); }

    private void openQrCamera() {
        startActivityForResult(new Intent(this, QrScannerActivity.class), REQUEST_QR_SCAN);
    }

    private void pastePairing() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Enlace o código del QR");
        input.setTextColor(style.text);
        input.setHintTextColor(style.muted);
        input.setBackgroundTintList(ColorStateList.valueOf(style.primary));
        LinearLayout dialogContent = style.column();
        dialogContent.setPadding(dp(24), dp(12), dp(24), dp(12));
        dialogContent.addView(input);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Conectar al PC")
                .setView(dialogContent)
                .setPositiveButton("Conectar", (window, which) -> processQr(input.getText().toString().trim()))
                .setNegativeButton("Cancelar", null).create();
        dialog.setOnShowListener(window -> {
            dialog.getWindow().setBackgroundDrawable(style.shape(style.surface, 28));
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(style.primary);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(style.primary);
        });
        dialog.show();
    }

    private void showStatus(String message) {
        if (!message.contentEquals(status.getText())) status.setText(message);
    }

    private void updateStatus() {
        retryButton.setVisibility(p2pToken.isEmpty() ? View.GONE : View.VISIBLE);
        connectionLabel.setText(!p2pToken.isEmpty() ? "Wi-Fi Direct · Sin router"
                : pairing.isEmpty() ? "Wi-Fi Direct o Wi-Fi local" : "Wi-Fi local · " + Uri.parse(pairing).getHost());
        if (audioRequested) {
            showStatus("Conectado".equals(AudioService.connectionStatus) ? AudioService.mode : AudioService.connectionStatus);
            return;
        }
        showStatus(pairing.isEmpty() ?
                (p2pToken.isEmpty() ? "Escanea el QR del PC para conectar." :
                        (p2pStatus.isEmpty() ? "P2P: esperando conexión directa del PC…" : p2pStatus)) :
                "PC enlazado: " + Uri.parse(pairing).getHost() + "\n" + AudioService.mode);
    }

    private void setP2pStatus(String message) {
        p2pStatus = message;
        updateStatus();
    }

    private void begin(String action) {
        if (pairing.isEmpty()) {
            Toast.makeText(this, p2pToken.isEmpty() ? "Primero escanea el QR del PC" :
                    "Busca y conecta este celular desde el PC", Toast.LENGTH_LONG).show();
            return;
        }
        pendingAction = action;
        if (!AudioService.PLAY.equals(action) && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD);
            return;
        }
        if (AudioService.SEND_INTERNAL.equals(action)) {
            MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
            startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_PROJECTION);
            return;
        }
        startAudio(action, 0, null);
    }

    private void startAudio(String action, int resultCode, Intent projectionData) {
        getPreferences(MODE_PRIVATE).edit().putInt("buffer", bufferSpinner.getSelectedItemPosition()).apply();
        Intent intent = new Intent(this, AudioService.class).setAction(action);
        intent.putExtra("pairing", pairing);
        intent.putExtra("buffer", bufferSpinner.getSelectedItemPosition());
        intent.putExtra("volume", volumeBar.getProgress() / 100f);
        if (projectionData != null) {
            intent.putExtra("projectionCode", resultCode);
            intent.putExtra("projectionData", projectionData);
        }
        audioRequested = true;
        AudioService.connectionStatus = "Conectando";
        startForegroundService(intent);
        showStatus("Conectando con el PC…");
        audioHandler.postDelayed(this::refreshAudioStatus, 700);
    }

    private void refreshAudioStatus() {
        if (!audioRequested) return;
        String state = AudioService.connectionStatus;
        if ("Conectado".equals(state)) {
            showStatus(AudioService.mode);
        } else if ("Conectando".equals(state) || state.startsWith("Reconectando")) {
            showStatus(state);
        } else {
            showStatus(state);
            audioRequested = false;
            return;
        }
        audioHandler.postDelayed(this::refreshAudioStatus, 700);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_QR_SCAN) {
            if (resultCode == RESULT_OK && data != null) {
                String text = data.getStringExtra("qr");
                if (text != null) processQr(text);
            }
            return;
        }
        if (requestCode == REQUEST_PROJECTION && resultCode == RESULT_OK && data != null) {
            startAudio(AudioService.SEND_INTERNAL, resultCode, data);
        }
    }

    private void processQr(String text) {
        Uri uri = Uri.parse(text);
        String profile = uri.isHierarchical() ? uri.getQueryParameter("profile") : null;
        if ("performance".equals(profile)) bufferSpinner.setSelection(0);
        else if ("balanced".equals(profile)) bufferSpinner.setSelection(1);
        else if ("quality".equals(profile)) bufferSpinner.setSelection(2);
        if ("redmiaudio".equals(uri.getScheme()) && "p2p".equals(uri.getHost())
                && uri.getPathSegments().size() == 1) {
            audioRequested = false;
            p2pToken = uri.getLastPathSegment();
            p2pStatus = "";
            pairing = "";
            getPreferences(MODE_PRIVATE).edit().putString("p2pToken", p2pToken)
                    .remove("p2pPeer").remove("pairing").apply();
            updateStatus();
            startP2p();
        } else if (("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                && uri.getHost() != null && uri.getPathSegments().size() == 1) {
            audioRequested = false;
            ++p2pGeneration;
            ServerSocket server = p2pServer;
            p2pServer = null;
            if (server != null) try { server.close(); } catch (IOException ignored) {}
            pairing = uri.buildUpon().clearQuery().build().toString();
            p2pToken = "";
            getPreferences(MODE_PRIVATE).edit().putString("pairing", pairing)
                    .remove("p2pToken").remove("p2pPeer").apply();
            updateStatus();
        } else {
            Toast.makeText(this, "Ese QR no es de ReExAudio", Toast.LENGTH_LONG).show();
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_P2P && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            startP2p();
        } else if (requestCode == REQUEST_P2P) {
            setP2pStatus("Concede el permiso «Dispositivos cercanos» para usar P2P.");
        } else if (requestCode == REQUEST_RECORD && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            begin(pendingAction);
        } else if (requestCode == REQUEST_RECORD) {
            Toast.makeText(this, "Se necesita permiso de audio para enviar sonido al PC", Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (status != null) {
            audioRequested = audioRequested || !"Detenido".equals(AudioService.mode);
            audioHandler.removeCallbacksAndMessages(null);
            updateStatus();
            if (audioRequested) audioHandler.postDelayed(this::refreshAudioStatus, 700);
        }
    }

    @Override protected void onPause() {
        audioHandler.removeCallbacksAndMessages(null);
        super.onPause();
    }

    @Override protected void onDestroy() {
        ++p2pGeneration;
        ServerSocket server = p2pServer;
        p2pServer = null;
        if (server != null) try { server.close(); } catch (IOException ignored) {}
        super.onDestroy();
    }

    private void startP2p() {
        String permission = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.NEARBY_WIFI_DEVICES :
                Manifest.permission.ACCESS_FINE_LOCATION;
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{permission}, REQUEST_P2P);
            return;
        }
        pairing = "";
        updateStatus();
        int generation = ++p2pGeneration;
        if (!startPairingServer()) return;
        try {
            WifiP2pManager manager = getSystemService(WifiP2pManager.class);
            if (manager == null) {
                setP2pStatus("Este celular no ofrece Wi‑Fi Direct. Usa Red local en el PC.");
                return;
            }
            WifiP2pManager.Channel channel = manager.initialize(this, getMainLooper(), null);
            if (channel == null) {
                setP2pStatus("No se pudo iniciar Wi‑Fi Direct. Usa Red local en el PC.");
                return;
            }
            setP2pStatus("Preparando Wi‑Fi Direct para que se conecte el PC…");
            manager.requestGroupInfo(channel, group -> {
                if (generation != p2pGeneration) return;
                if (group == null) {
                    createP2pGroup(manager, channel, generation);
                } else {
                    manager.removeGroup(channel, new WifiP2pManager.ActionListener() {
                        public void onSuccess() { createP2pGroup(manager, channel, generation); }
                        public void onFailure(int reason) {
                            if (generation == p2pGeneration)
                                setP2pStatus("No se pudo reiniciar Wi‑Fi Direct (" + reason + "). Reintenta.");
                        }
                    });
                }
            });
        } catch (RuntimeException error) {
            android.util.Log.e("ReExAudio", "Wi-Fi Direct failed", error);
            setP2pStatus("No se pudo iniciar Wi‑Fi Direct: " + error.getMessage());
        }
    }

    private boolean startPairingServer() {
        try {
            ServerSocket previous = p2pServer;
            if (previous != null) previous.close();
            ServerSocket server = new ServerSocket(53318);
            p2pServer = server;
            String token = p2pToken;
            new Thread(() -> {
                try {
                    while (!server.isClosed()) {
                        try (Socket client = server.accept()) {
                            client.setSoTimeout(5000);
                            String received = new BufferedReader(new InputStreamReader(client.getInputStream()))
                                    .readLine();
                            if (!token.equals(received)) continue;
                            String pcAddress = client.getInetAddress().getHostAddress();
                            client.getOutputStream().write("OK\n".getBytes());
                            pairing = "http://" + pcAddress + ":53317/" + token;
                            runOnUiThread(this::updateStatus);
                        }
                    }
                } catch (Exception error) {
                    if (server == p2pServer)
                        runOnUiThread(() -> setP2pStatus("P2P: " + error.getMessage()));
                } finally {
                    if (server == p2pServer) p2pServer = null;
                    try { server.close(); } catch (IOException ignored) {}
                }
            }, "ReExAudioPairing").start();
            return true;
        } catch (IOException error) {
            setP2pStatus("No se pudo escuchar la conexión P2P: " + error.getMessage());
            return false;
        }
    }

    private void createP2pGroup(WifiP2pManager manager, WifiP2pManager.Channel channel,
                                int generation) {
        if (generation != p2pGeneration) return;
        manager.createGroup(channel, new WifiP2pManager.ActionListener() {
            public void onSuccess() {
                if (generation == p2pGeneration)
                    setP2pStatus("Wi‑Fi Direct listo. Pulsa Buscar celular en el PC y conéctalo.");
            }
            public void onFailure(int reason) {
                if (generation == p2pGeneration)
                    setP2pStatus("No se pudo crear el grupo Wi‑Fi Direct (" + reason + "). " +
                            "Activa Wi‑Fi y reintenta.");
            }
        });
    }
}
