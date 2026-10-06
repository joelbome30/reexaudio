package com.joel.redmiaudio;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Bundle;
import android.os.Build;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;

public class MainActivity extends Activity {
    private static final int REQUEST_RECORD = 100;
    private static final int REQUEST_PROJECTION = 101;
    private static final int REQUEST_P2P = 102;
    private String pairing;
    private String p2pToken;
    private String pendingAction;
    private TextView status;
    private Spinner bufferSpinner;
    private SeekBar volumeBar;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        pairing = getPreferences(MODE_PRIVATE).getString("pairing", "");
        p2pToken = getPreferences(MODE_PRIVATE).getString("p2pToken", "");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(24), dp(32), dp(24), dp(24));
        layout.setBackgroundColor(0xff17191d);
        setContentView(layout);

        TextView title = label("Redmi Audio", 28);
        title.setTypeface(null, 1);
        layout.addView(title);
        status = label("", 16);
        status.setPadding(0, dp(12), 0, dp(18));
        layout.addView(status);

        addButton(layout, "Escanear QR del PC", () -> new IntentIntegrator(this)
                .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                .setPrompt("Escanea el QR de Redmi Audio en el PC")
                .setBeepEnabled(false).initiateScan());
        addButton(layout, "Escuchar el PC", () -> begin(AudioService.PLAY));
        addButton(layout, "Enviar audio del celular al PC", () -> begin(AudioService.SEND_INTERNAL));
        addButton(layout, "Enviar micrófono al PC", () -> begin(AudioService.SEND_MIC));
        addButton(layout, "Detener", () -> {
            Intent intent = new Intent(this, AudioService.class).setAction(AudioService.STOP);
            startService(intent);
            status.setText("Audio detenido");
        });

        TextView bufferLabel = label("Retardo de reproducción", 16);
        bufferLabel.setPadding(0, dp(20), 0, 0);
        layout.addView(bufferLabel);
        bufferSpinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Bajo", "Estable"});
        bufferSpinner.setAdapter(adapter);
        bufferSpinner.setSelection(getPreferences(MODE_PRIVATE).getInt("buffer", 0));
        layout.addView(bufferSpinner);

        TextView volumeLabel = label("Volumen en el celular", 16);
        volumeLabel.setPadding(0, dp(20), 0, 0);
        layout.addView(volumeLabel);
        volumeBar = new SeekBar(this);
        volumeBar.setMax(100);
        volumeBar.setProgress(getPreferences(MODE_PRIVATE).getInt("volume", 100));
        layout.addView(volumeBar);
        volumeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int progress, boolean user) {
                if (user) {
                    getPreferences(MODE_PRIVATE).edit().putInt("volume", progress).apply();
                    AudioService.setVolume(progress / 100f);
                }
            }
            public void onStartTrackingTouch(SeekBar bar) {}
            public void onStopTrackingTouch(SeekBar bar) {}
        });
        updateStatus();
    }

    private int dp(int value) {
        return (int) (getResources().getDisplayMetrics().density * value + .5f);
    }

    private TextView label(String text, int size) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(0xfff2f4f5);
        return view;
    }

    private void addButton(LinearLayout layout, String text, Runnable action) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(52));
        params.bottomMargin = dp(8);
        layout.addView(button, params);
        button.setOnClickListener(v -> action.run());
    }

    private void updateStatus() {
        status.setText(pairing.isEmpty() ?
                (p2pToken.isEmpty() ? "Escanea el QR del PC para conectar." :
                        "P2P: esperando conexión directa del PC…") :
                "PC enlazado: " + Uri.parse(pairing).getHost() + "\n" + AudioService.mode);
    }

    private void begin(String action) {
        if (pairing.isEmpty()) {
            Toast.makeText(this, "Primero escanea el QR del PC", Toast.LENGTH_LONG).show();
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
        startForegroundService(intent);
        status.setText("Conectando con el PC…");
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        IntentResult scan = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (scan != null) {
            if (scan.getContents() != null) {
                Uri uri = Uri.parse(scan.getContents());
                if ("redmiaudio".equals(uri.getScheme()) && "p2p".equals(uri.getHost())
                        && uri.getPathSegments().size() == 1) {
                    p2pToken = uri.getLastPathSegment();
                    pairing = "";
                    getPreferences(MODE_PRIVATE).edit().putString("p2pToken", p2pToken)
                            .remove("pairing").apply();
                    updateStatus();
                    startP2p();
                } else if (("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                        && uri.getHost() != null && uri.getPathSegments().size() == 1) {
                    pairing = uri.buildUpon().clearQuery().build().toString();
                    p2pToken = "";
                    getPreferences(MODE_PRIVATE).edit().putString("pairing", pairing)
                            .remove("p2pToken").apply();
                    updateStatus();
                } else {
                    Toast.makeText(this, "Ese QR no es de Redmi Audio", Toast.LENGTH_LONG).show();
                }
            }
            return;
        }
        if (requestCode == REQUEST_PROJECTION && resultCode == RESULT_OK && data != null) {
            startAudio(AudioService.SEND_INTERNAL, resultCode, data);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_P2P && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            startP2p();
        } else if (requestCode == REQUEST_RECORD && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            begin(pendingAction);
        } else if (requestCode == REQUEST_RECORD) {
            Toast.makeText(this, "Se necesita permiso de audio para enviar sonido al PC", Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (status != null) updateStatus();
    }

    private void startP2p() {
        String permission = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.NEARBY_WIFI_DEVICES :
                Manifest.permission.ACCESS_FINE_LOCATION;
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{permission}, REQUEST_P2P);
            return;
        }
        new Thread(() -> {
            try (ServerSocket server = new ServerSocket(53318)) {
                server.setSoTimeout(120000);
                while (true) {
                    try (Socket client = server.accept()) {
                        client.setSoTimeout(5000);
                        String received = new BufferedReader(new InputStreamReader(client.getInputStream()))
                                .readLine();
                        if (!p2pToken.equals(received)) continue;
                        String pcAddress = client.getInetAddress().getHostAddress();
                        client.getOutputStream().write("OK\n".getBytes());
                        pairing = "http://" + pcAddress + ":53317/" + p2pToken;
                        getPreferences(MODE_PRIVATE).edit().putString("pairing", pairing).apply();
                        runOnUiThread(this::updateStatus);
                        break;
                    }
                }
            } catch (Exception error) {
                runOnUiThread(() -> status.setText("P2P: " + error.getMessage()));
            }
        }, "RedmiAudioP2P").start();

        WifiP2pManager manager = getSystemService(WifiP2pManager.class);
        WifiP2pManager.Channel channel = manager.initialize(this, getMainLooper(), null);
        manager.createGroup(channel, new WifiP2pManager.ActionListener() {
            public void onSuccess() { status.setText("P2P listo. Pulsa «Buscar celular» en el PC."); }
            public void onFailure(int reason) {
                status.setText("P2P: comprueba que Wi‑Fi Direct esté activado (" + reason + ")");
            }
        });
    }
}
