package com.joel.redmiaudio;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.*;
import android.util.Size;
import android.view.*;
import android.widget.*;
import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Comparator;

/** Live QR preview. Camera and decoding share one worker; frames are never saved. */
public class QrScannerActivity extends Activity implements TextureView.SurfaceTextureListener {
    private TextureView preview;
    private FrameLayout frame;
    private TextView hint;
    private final HandlerThread thread = new HandlerThread("ReExAudioQrCamera");
    private Handler cameraHandler;
    private CameraDevice camera;
    private CameraCaptureSession capture;
    private ImageReader images;
    private Surface surface;
    private boolean opening;
    private volatile boolean resumed;
    private volatile int generation;
    private volatile boolean found;
    private long lastDecode;
    private final QRCodeReader decoder = new QRCodeReader();

    @Override public void onCreate(Bundle state) {
        AppStyle style = new AppStyle(this);
        style.apply();
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(style.background);
        content.setPadding(style.dp(20), style.dp(24), style.dp(20), style.dp(16));
        content.addView(style.title("Conectar al PC", 28));
        hint = style.label("Apunta al QR de ReExAudio. Se detectará automáticamente.", 16, true);
        hint.setPadding(0, style.dp(10), 0, style.dp(20));
        hint.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(hint);
        frame = new FrameLayout(this);
        frame.setBackground(style.shape(Color.BLACK, 24));
        frame.setClipToOutline(true);
        preview = new TextureView(this);
        preview.setSurfaceTextureListener(this);
        frame.addView(preview, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        View guide = new View(this) {
            private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(android.graphics.Canvas canvas) {
                float size = Math.min(getWidth(), getHeight()) * .72f;
                float left = (getWidth() - size) / 2, top = (getHeight() - size) / 2;
                float length = size * .15f;
                paint.setColor(style.primary);
                paint.setStrokeWidth(style.dp(4));
                paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
                for (int x = 0; x < 2; x++) for (int y = 0; y < 2; y++) {
                    float px = left + x * size, py = top + y * size;
                    canvas.drawLine(px, py, px + (x == 0 ? length : -length), py, paint);
                    canvas.drawLine(px, py, px, py + (y == 0 ? length : -length), paint);
                }
            }
        };
        guide.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        frame.addView(guide, new FrameLayout.LayoutParams(-1, -1));
        content.addView(frame, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView note = style.label("Mantén el código dentro del marco. No hace falta tomar una foto.", 14, true);
        note.setPadding(0, style.dp(16), 0, style.dp(6));
        content.addView(note);
        style.addButton(content, "Volver", false, this::finish);
        setContentView(content);
        thread.start();
        cameraHandler = new Handler(thread.getLooper());
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 1);
    }

    @Override protected void onResume() {
        super.onResume();
        found = false;
        resumed = true;
        startCamera();
    }

    @Override protected void onPause() {
        resumed = false;
        generation++;
        cameraHandler.post(this::closeCamera);
        super.onPause();
    }

    @Override protected void onDestroy() {
        cameraHandler.post(() -> { closeCamera(); thread.quitSafely(); });
        super.onDestroy();
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 1 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED)
            startCamera();
        else hint.setText("Se necesita permiso de cámara. Puedes volver y pegar el enlace del PC.");
    }

    private boolean current(int epoch) { return resumed && generation == epoch && !found; }

    private void startCamera() {
        if (!resumed || !preview.isAvailable()
                || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        int epoch = generation;
        SurfaceTexture texture = preview.getSurfaceTexture();
        cameraHandler.post(() -> {
            if (!current(epoch) || opening || camera != null) return;
            opening = true;
            try {
                CameraManager manager = getSystemService(CameraManager.class);
                String selected = null;
                for (String id : manager.getCameraIdList()) {
                    Integer facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                    if (selected == null) selected = id;
                    if (Integer.valueOf(CameraCharacteristics.LENS_FACING_BACK).equals(facing)) {
                        selected = id;
                        break;
                    }
                }
                if (selected == null) throw new IllegalStateException("No hay cámara disponible");
                CameraCharacteristics info = manager.getCameraCharacteristics(selected);
                StreamConfigurationMap map = info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map == null) throw new IllegalStateException("Cámara incompatible");
                Size[] previews = map.getOutputSizes(SurfaceTexture.class);
                Size size = Arrays.stream(map.getOutputSizes(ImageFormat.YUV_420_888))
                        .filter(s -> Arrays.asList(previews).contains(s))
                        .min(Comparator.comparingLong(s -> Math.abs((long)s.getWidth() * s.getHeight() - 640L * 480)))
                        .orElseThrow(() -> new IllegalStateException("No hay formato de cámara compatible"));
                Integer orientation = info.get(CameraCharacteristics.SENSOR_ORIENTATION);
                boolean swap = orientation != null && orientation % 180 != 0;
                float ratio = swap ? (float)size.getHeight() / size.getWidth()
                        : (float)size.getWidth() / size.getHeight();
                runOnUiThread(() -> {
                    if (!current(epoch)) return;
                    int width = frame.getWidth(), height = frame.getHeight();
                    int fittedWidth = Math.min(width, (int)(height * ratio));
                    preview.setLayoutParams(new FrameLayout.LayoutParams(fittedWidth,
                            (int)(fittedWidth / ratio), Gravity.CENTER));
                });
                texture.setDefaultBufferSize(size.getWidth(), size.getHeight());
                surface = new Surface(texture);
                images = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888, 2);
                images.setOnImageAvailableListener(reader -> decode(reader, epoch), cameraHandler);
                manager.openCamera(selected, new CameraDevice.StateCallback() {
                    @Override public void onOpened(CameraDevice device) {
                        if (!current(epoch)) { device.close(); return; }
                        opening = false;
                        camera = device;
                        configure(epoch, info);
                    }
                    @Override public void onDisconnected(CameraDevice device) {
                        device.close();
                        if (current(epoch)) failure("La cámara se desconectó", epoch);
                    }
                    @Override public void onError(CameraDevice device, int error) {
                        device.close();
                        if (current(epoch)) failure("No se pudo abrir la cámara. Vuelve a intentarlo.", epoch);
                    }
                }, cameraHandler);
            } catch (Exception error) {
                failure("No se pudo abrir la cámara. Vuelve a intentarlo.", epoch);
            }
        });
    }

    private void configure(int epoch, CameraCharacteristics info) {
        try {
            CaptureRequest.Builder request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            request.addTarget(surface);
            request.addTarget(images.getSurface());
            int[] modes = info.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            if (modes != null && Arrays.stream(modes).anyMatch(m -> m == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE))
                request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            camera.createCaptureSession(Arrays.asList(surface, images.getSurface()), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession session) {
                    if (!current(epoch)) { session.close(); return; }
                    capture = session;
                    try { session.setRepeatingRequest(request.build(), null, cameraHandler); }
                    catch (CameraAccessException | IllegalStateException error) { failure("No se pudo iniciar la cámara", epoch); }
                }
                @Override public void onConfigureFailed(CameraCaptureSession session) {
                    failure("No se pudo iniciar la cámara", epoch);
                }
            }, cameraHandler);
        } catch (CameraAccessException | IllegalStateException error) {
            failure("No se pudo iniciar la cámara", epoch);
        }
    }

    private void decode(ImageReader reader, int epoch) {
        if (!current(epoch)) return;
        try (Image image = reader.acquireLatestImage()) {
            if (image == null || SystemClock.elapsedRealtime() - lastDecode < 150) return;
            lastDecode = SystemClock.elapsedRealtime();
            int width = image.getWidth(), height = image.getHeight();
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            byte[] luminance = new byte[width * height];
            int start = buffer.position();
            for (int y = 0; y < height; y++) {
                int row = start + y * plane.getRowStride();
                for (int x = 0; x < width; x++)
                    luminance[y * width + x] = buffer.get(row + x * plane.getPixelStride());
            }
            String text = decoder.decode(new BinaryBitmap(new HybridBinarizer(new PlanarYUVLuminanceSource(
                    luminance, width, height, 0, 0, width, height, false)))).getText();
            Uri uri = Uri.parse(text);
            boolean valid = uri.isHierarchical() && uri.getHost() != null && uri.getPathSegments().size() == 1
                    && (("redmiaudio".equals(uri.getScheme()) && "p2p".equals(uri.getHost()))
                    || "http".equals(uri.getScheme()) || "https".equals(uri.getScheme()));
            if (!valid) {
                runOnUiThread(() -> { if (current(epoch)) hint.setText("Ese QR no es de ReExAudio. Apunta al QR del PC."); });
                return;
            }
            runOnUiThread(() -> {
                if (!current(epoch)) return;
                found = true;
                setResult(RESULT_OK, new Intent().putExtra("qr", text));
                finish();
            });
        } catch (ReaderException ignored) {
            // Most preview frames do not contain a readable QR yet.
        } catch (IllegalStateException ignored) {
            // The camera may have closed while the activity was leaving.
        } finally { decoder.reset(); }
    }

    private void failure(String message, int epoch) {
        if (!current(epoch)) return;
        closeCamera();
        runOnUiThread(() -> { if (current(epoch)) hint.setText(message); });
    }

    private void closeCamera() {
        if (capture != null) { capture.close(); capture = null; }
        if (camera != null) { camera.close(); camera = null; }
        if (images != null) { images.close(); images = null; }
        if (surface != null) { surface.release(); surface = null; }
        opening = false;
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) { startCamera(); }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {}
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
        generation++;
        cameraHandler.post(this::closeCamera);
        return true;
    }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) {}
}
