package com.joel.redmiaudio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public class AudioService extends Service {
    public static final String PLAY = "com.joel.redmiaudio.PLAY";
    public static final String SEND_MIC = "com.joel.redmiaudio.SEND_MIC";
    public static final String SEND_INTERNAL = "com.joel.redmiaudio.SEND_INTERNAL";
    public static final String STOP = "com.joel.redmiaudio.STOP";
    public static volatile String mode = "Detenido";

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .pingInterval(15, TimeUnit.SECONDS).build();
    private static volatile AudioTrack currentTrack;
    private volatile boolean running;
    private volatile WebSocket socket;
    private volatile Thread worker;
    private volatile AudioRecord recorder;
    private volatile MediaProjection projection;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(12);
    private int bufferChunks;
    private long session;

    @Override public IBinder onBind(Intent intent) { return null; }

    public static void setVolume(float volume) {
        AudioTrack track = currentTrack;
        if (track != null) track.setVolume(volume);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        cleanup();
        String action = intent.getAction();
        String pairing = intent.getStringExtra("pairing");
        if (pairing == null || !(PLAY.equals(action) || SEND_MIC.equals(action) || SEND_INTERNAL.equals(action))) {
            stopSelf();
            return START_NOT_STICKY;
        }
        bufferChunks = intent.getIntExtra("buffer", 0) == 0 ? 2 : 5;
        running = true;
        session++;
        long activeSession = session;
        mode = PLAY.equals(action) ? "Escuchando el PC" :
                (SEND_MIC.equals(action) ? "Enviando micrófono" : "Enviando audio del celular");
        int type = PLAY.equals(action) ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK :
                (SEND_MIC.equals(action) ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE :
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        startForeground(1, notification(mode), type);
        holdAwake();

        try {
            if (PLAY.equals(action)) {
                createPlayer(intent.getFloatExtra("volume", 1f));
            } else {
                createRecorder(action, intent);
            }
        } catch (Exception error) {
            Log.e("RedmiAudio", "Unable to start audio", error);
            mode = "Error al iniciar audio: " + error.getMessage();
            stopSelf();
            return START_NOT_STICKY;
        }

        String endpoint = PLAY.equals(action) ? "/listen" : "/send";
        Request request = new Request.Builder().url(pairing.replaceFirst("^http", "ws") + endpoint).build();
        socket = CLIENT.newWebSocket(request, new WebSocketListener() {
            @Override public void onOpen(WebSocket webSocket, Response response) {
                if (activeSession != session || !running) return;
                if (PLAY.equals(action)) startPlaybackWorker();
                else startCaptureWorker(webSocket);
            }

            @Override public void onMessage(WebSocket webSocket, ByteString bytes) {
                if (activeSession != session || !running || !PLAY.equals(action)) return;
                if (queue.remainingCapacity() == 0 || queue.size() > 8) queue.clear();
                queue.offer(bytes.toByteArray());
            }

            @Override public void onFailure(WebSocket webSocket, Throwable error, Response response) {
                if (activeSession == session && running) {
                    Log.e("RedmiAudio", "Connection failed", error);
                    mode = "No se pudo conectar al PC";
                    stopSelf();
                }
            }
            @Override public void onClosed(WebSocket webSocket, int code, String reason) {
                if (activeSession == session && running) stopSelf();
            }
        });
        return START_NOT_STICKY;
    }

    private Notification notification(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("audio", "Redmi Audio",
                NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 1, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 2,
                new Intent(this, AudioService.class).setAction(STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "audio")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Redmi Audio")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_media_pause, "Detener", stop)
                .build();
    }

    private void holdAwake() {
        PowerManager power = getSystemService(PowerManager.class);
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RedmiAudio:stream");
        wakeLock.acquire();
        WifiManager wifi = getSystemService(WifiManager.class);
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "RedmiAudio:network");
        wifiLock.acquire();
    }

    private void createPlayer(float volume) {
        int min = AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT);
        AudioFormat format = new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build();
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(Math.max(min, 16384))
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build();
        if (track.getState() != AudioTrack.STATE_INITIALIZED) throw new IllegalStateException("AudioTrack");
        track.setVolume(volume);
        currentTrack = track;
        track.play();
    }

    private void startPlaybackWorker() {
        worker = new Thread(() -> {
            try {
                while (running) {
                    if (queue.size() < bufferChunks) {
                        Thread.sleep(5);
                        continue;
                    }
                    byte[] data = queue.poll(500, TimeUnit.MILLISECONDS);
                    if (data != null && currentTrack != null) {
                        currentTrack.write(data, 0, data.length, AudioTrack.WRITE_BLOCKING);
                    }
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "RedmiAudioPlayback");
        worker.start();
    }

    private void createRecorder(String action, Intent intent) {
        int min = AudioRecord.getMinBufferSize(48000, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        AudioFormat format = new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build();
        AudioRecord.Builder builder = new AudioRecord.Builder().setAudioFormat(format)
                .setBufferSizeInBytes(Math.max(min, 8192));
        if (SEND_MIC.equals(action)) {
            builder.setAudioSource(MediaRecorder.AudioSource.MIC);
        } else {
            Intent data = (Intent) intent.getParcelableExtra("projectionData");
            if (data == null) throw new IllegalArgumentException("Falta permiso de captura");
            MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
            projection = manager.getMediaProjection(intent.getIntExtra("projectionCode", 0), data);
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() {
                    if (running) stopSelf();
                }
            }, new Handler(Looper.getMainLooper()));
            AudioPlaybackCaptureConfiguration capture = new AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .build();
            builder.setAudioPlaybackCaptureConfig(capture);
        }
        recorder = builder.build();
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("AudioRecord");
        recorder.startRecording();
    }

    private void startCaptureWorker(WebSocket webSocket) {
        worker = new Thread(() -> {
            byte[] buffer = new byte[4096];
            while (running && recorder != null) {
                int count = recorder.read(buffer, 0, buffer.length, AudioRecord.READ_BLOCKING);
                if (count <= 0) break;
                if (webSocket.queueSize() < 262144) webSocket.send(ByteString.of(buffer, 0, count));
            }
        }, "RedmiAudioCapture");
        worker.start();
    }

    private void cleanup() {
        running = false;
        session++;
        WebSocket oldSocket = socket;
        socket = null;
        if (oldSocket != null) oldSocket.cancel();
        Thread oldWorker = worker;
        worker = null;
        if (oldWorker != null) oldWorker.interrupt();
        AudioRecord oldRecorder = recorder;
        recorder = null;
        if (oldRecorder != null) {
            try { oldRecorder.stop(); } catch (IllegalStateException ignored) {}
            oldRecorder.release();
        }
        AudioTrack oldTrack = currentTrack;
        currentTrack = null;
        if (oldTrack != null) {
            oldTrack.pause();
            oldTrack.flush();
            oldTrack.release();
        }
        if (projection != null) {
            projection.stop();
            projection = null;
        }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        queue.clear();
    }

    @Override public void onDestroy() {
        cleanup();
        mode = "Detenido";
        super.onDestroy();
    }
}
