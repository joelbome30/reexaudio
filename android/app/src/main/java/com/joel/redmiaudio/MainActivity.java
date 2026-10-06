package com.joel.redmiaudio;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Bundle;
import android.os.Build;
import android.provider.MediaStore;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.File;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;

public class MainActivity extends Activity {
    private static final int REQUEST_RECORD = 100;
    private static final int REQUEST_PROJECTION = 101;
    private static final int REQUEST_P2P = 102;
    private static final int REQUEST_QR_PHOTO = 103;
    private String pairing;
    private String p2pToken;
    private String pendingAction;
    private TextView status;
    private Spinner bufferSpinner;
    private SeekBar volumeBar;
    private File qrPhoto;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        pairing = getPreferences(MODE_PRIVATE).getString("pairing", "");
        p2pToken = getPreferences(MODE_PRIVATE).getString("p2pToken", "");
        if (state != null && state.getString("qrPhoto") != null)
            qrPhoto = new File(state.getString("qrPhoto"));

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(24), dp(32), dp(24), dp(24));
        layout.setBackgroundColor(0xff17191d);
        setContentView(layout);

        String appVersion = "";
        try {
            appVersion = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException ignored) {}
        TextView title = label("ReExAudio", 28);
        title.setTypeface(null, 1);
        layout.addView(title);
        TextView version = label("Versión " + appVersion, 12);
        version.setTextColor(0xffaab1ba);
        layout.addView(version);
        status = label("", 16);
        status.setPadding(0, dp(12), 0, dp(18));
        layout.addView(status);

        addButton(layout, "Escanear QR del PC", this::openQrCamera);
        addButton(layout, "Pegar enlace o código", this::pastePairing);
        addButton(layout, "Escuchar el PC", () -> begin(AudioService.PLAY));
        addButton(layout, "Enviar audio del celular al PC", () -> begin(AudioService.SEND_INTERNAL));
        addButton(layout, "Enviar micrófono al PC", () -> begin(AudioService.SEND_MIC));
        addButton(layout, "Detener", () -> {
            Intent intent = new Intent(this, AudioService.class).setAction(AudioService.STOP);
            startService(intent);
            status.setText("Audio detenido");
        });

        TextView bufferLabel = label("Perfil de retardo (al iniciar audio)", 16);
        bufferLabel.setPadding(0, dp(20), 0, 0);
        layout.addView(bufferLabel);
        bufferSpinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Rendimiento · menos retardo", "Equilibrado", "Calidad · más estabilidad"}) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                ((TextView) view).setTextColor(0xfff2f4f5);
                return view;
            }
        };
        bufferSpinner.setAdapter(adapter);
        bufferSpinner.setSelection(getPreferences(MODE_PRIVATE).getInt("buffer", 1));
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

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (qrPhoto != null) state.putString("qrPhoto", qrPhoto.getAbsolutePath());
    }

    private void openQrCamera() {
        try {
            qrPhoto = File.createTempFile("reexaudio-qr-", ".jpg", getCacheDir());
            Uri target = FileProvider.getUriForFile(this, getPackageName() + ".files", qrPhoto);
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, target);
            intent.setClipData(ClipData.newRawUri("ReExAudio QR", target));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            startActivityForResult(intent, REQUEST_QR_PHOTO);
        } catch (Exception error) {
            Toast.makeText(this, "No se pudo abrir la cámara: " + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void pastePairing() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Enlace o código del QR");
        new AlertDialog.Builder(this).setTitle("Conectar al PC").setView(input)
                .setPositiveButton("Conectar", (dialog, which) -> processQr(input.getText().toString().trim()))
                .setNegativeButton("Cancelar", null).show();
    }

    private void decodeQrPhoto() {
        File image = qrPhoto;
        if (image == null || !image.exists()) {
            Toast.makeText(this, "No se guardó la foto del QR", Toast.LENGTH_LONG).show();
            return;
        }
        status.setText("Leyendo QR…");
        new Thread(() -> {
            String decoded = null;
            try {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(image.getAbsolutePath(), options);
                options.inSampleSize = 1;
                while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 1800)
                    options.inSampleSize *= 2;
                options.inJustDecodeBounds = false;
                Bitmap photo = BitmapFactory.decodeFile(image.getAbsolutePath(), options);
                if (photo == null) throw new IllegalStateException("Foto vacía");
                for (int degrees = 0; degrees < 360 && decoded == null; degrees += 90) {
                    Matrix matrix = new Matrix();
                    matrix.postRotate(degrees);
                    Bitmap rotated = degrees == 0 ? photo : Bitmap.createBitmap(photo, 0, 0,
                            photo.getWidth(), photo.getHeight(), matrix, false);
                    int width = rotated.getWidth(), height = rotated.getHeight();
                    int[] pixels = new int[width * height];
                    rotated.getPixels(pixels, 0, width, 0, 0, width, height);
                    MultiFormatReader reader = new MultiFormatReader();
                    Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
                    hints.put(DecodeHintType.POSSIBLE_FORMATS, Arrays.asList(BarcodeFormat.QR_CODE));
                    hints.put(DecodeHintType.TRY_HARDER, true);
                    reader.setHints(hints);
                    try {
                        decoded = reader.decodeWithState(new BinaryBitmap(
                                new HybridBinarizer(new RGBLuminanceSource(width, height, pixels)))).getText();
                    } catch (Exception ignored) {}
                    if (rotated != photo) rotated.recycle();
                }
                photo.recycle();
            } catch (Exception error) {
                android.util.Log.e("ReExAudio", "QR decode failed", error);
            } finally {
                image.delete();
            }
            String result = decoded;
            runOnUiThread(() -> {
                if (result == null) {
                    status.setText("No encontré un QR. Acerca la cámara y toma otra foto.");
                } else {
                    processQr(result);
                }
            });
        }, "ReExAudioQrDecode").start();
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
        if (requestCode == REQUEST_QR_PHOTO) {
            if (resultCode == RESULT_OK) decodeQrPhoto();
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
            Toast.makeText(this, "Ese QR no es de ReExAudio", Toast.LENGTH_LONG).show();
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

        try {
            WifiP2pManager manager = getSystemService(WifiP2pManager.class);
            if (manager == null) {
                status.setText("Este celular no ofrece Wi‑Fi Direct. Usa Red local en el PC.");
                return;
            }
            WifiP2pManager.Channel channel = manager.initialize(this, getMainLooper(), null);
            if (channel == null) {
                status.setText("No se pudo iniciar Wi‑Fi Direct. Usa Red local en el PC.");
                return;
            }
            manager.createGroup(channel, new WifiP2pManager.ActionListener() {
                public void onSuccess() { status.setText("P2P listo. Pulsa «Buscar celular» en el PC."); }
                public void onFailure(int reason) {
                    status.setText("P2P: comprueba que Wi‑Fi Direct esté activado (" + reason + ")");
                }
            });
        } catch (RuntimeException error) {
            android.util.Log.e("ReExAudio", "Wi-Fi Direct failed", error);
            status.setText("No se pudo iniciar Wi‑Fi Direct: " + error.getMessage());
        }
    }
}
