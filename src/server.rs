//! HTTP/WebSocket compatibility with the native Android client.
use crate::{
    VERSION,
    audio::Routing,
    config::{Profile, State, asset},
};
use anyhow::{Context, Result, bail};
use axum::{
    Router,
    body::Body,
    extract::{
        Path, Query, State as AppState, WebSocketUpgrade,
        ws::{Message, WebSocket},
    },
    http::{StatusCode, header},
    response::{Html, IntoResponse, Response},
    routing::get,
};
use futures_util::{SinkExt, StreamExt};
use serde::Deserialize;
use std::{path::PathBuf, process::Stdio, sync::Arc, time::Duration};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    process::Command,
    sync::Semaphore,
    time::{Instant, interval, timeout},
};
use tokio_util::{io::ReaderStream, sync::CancellationToken, task::TaskTracker};

const WRITE_TIMEOUT: Duration = Duration::from_secs(2);
const PEER_TIMEOUT: Duration = Duration::from_secs(35);
const MAX_MESSAGE: usize = 262_144;
#[derive(Clone)]
pub struct Server {
    pub state: State,
    pub apk: PathBuf,
    pub cancel: CancellationToken,
    pub tasks: TaskTracker,
    pub capture_program: PathBuf,
    pub playback_program: PathBuf,
    sessions: Arc<Semaphore>,
}
impl Server {
    pub fn new(state: State) -> Self {
        let installed = asset("app.apk");
        let apk = if installed.exists() {
            installed
        } else {
            asset("android/app/build/outputs/apk/debug/app-debug.apk")
        };
        Self {
            state,
            apk,
            capture_program: "parec".into(),
            playback_program: "pacat".into(),
            cancel: CancellationToken::new(),
            tasks: TaskTracker::new(),
            sessions: Arc::new(Semaphore::new(8)),
        }
    }
    pub fn router(&self) -> Router {
        Router::new()
            .route("/{token}", get(landing))
            .route("/{token}/{endpoint}", get(endpoint))
            .with_state(self.clone())
    }
}
#[derive(Deserialize)]
struct Params {
    profile: Option<String>,
}
async fn landing(AppState(server): AppState<Server>, Path(token): Path<String>) -> Response {
    if token != server.state.token {
        return StatusCode::NOT_FOUND.into_response();
    }
    let download = if server.apk.is_file() {
        format!(
            "<p><a href='/{token}/ReExAudio-{VERSION}.apk'>Descargar ReExAudio {VERSION}</a></p>"
        )
    } else {
        String::new()
    };
    ([(header::CACHE_CONTROL, "no-store")], Html(format!("<!doctype html><html lang='es'><meta name='viewport' content='width=device-width,initial-scale=1'><title>ReExAudio</title><style>body{{font:20px system-ui;max-width:35rem;margin:4rem auto;padding:1rem;background:#17191d;color:white}}a{{color:#b5ceaa}}</style><h1>ReExAudio</h1><p>Instala la app Android y escanea el QR del PC.</p>{download}</html>"))).into_response()
}
async fn endpoint(
    AppState(server): AppState<Server>,
    Path((token, endpoint)): Path<(String, String)>,
    Query(params): Query<Params>,
    ws: Result<WebSocketUpgrade, axum::extract::ws::rejection::WebSocketUpgradeRejection>,
) -> Response {
    if token != server.state.token {
        return StatusCode::NOT_FOUND.into_response();
    }
    if endpoint == "app.apk" || endpoint == format!("ReExAudio-{VERSION}.apk") {
        let Ok(file) = tokio::fs::File::open(&server.apk).await else {
            return StatusCode::NOT_FOUND.into_response();
        };
        let Ok(meta) = file.metadata().await else {
            return StatusCode::INTERNAL_SERVER_ERROR.into_response();
        };
        return (
            [
                (
                    header::CONTENT_TYPE,
                    "application/vnd.android.package-archive".to_string(),
                ),
                (header::CONTENT_LENGTH, meta.len().to_string()),
                (header::CACHE_CONTROL, "no-store".into()),
                (
                    header::CONTENT_DISPOSITION,
                    format!("attachment; filename=ReExAudio-{VERSION}.apk"),
                ),
            ],
            Body::from_stream(ReaderStream::new(file)),
        )
            .into_response();
    }
    if endpoint != "listen" && endpoint != "send" {
        return StatusCode::NOT_FOUND.into_response();
    }
    let Ok(ws) = ws else {
        return StatusCode::BAD_REQUEST.into_response();
    };
    let Ok(permit) = server.sessions.clone().try_acquire_owned() else {
        return StatusCode::SERVICE_UNAVAILABLE.into_response();
    };
    if server.cancel.is_cancelled() {
        return StatusCode::SERVICE_UNAVAILABLE.into_response();
    }
    let cfg = match server.state.config() {
        Ok(cfg) => cfg,
        Err(e) => {
            eprintln!("Configuración: {e:#}");
            return StatusCode::INTERNAL_SERVER_ERROR.into_response();
        }
    };
    let profile = params
        .profile
        .as_deref()
        .map(Profile::from_name)
        .unwrap_or(cfg.profile);
    if endpoint == "send" && (cfg.reverse_sink.is_empty() || cfg.reverse_sink == crate::VIRTUAL) {
        return (
            StatusCode::CONFLICT,
            "Selecciona una salida física en el PC",
        )
            .into_response();
    }
    let task = server.tasks.token();
    ws.max_message_size(MAX_MESSAGE)
        .max_frame_size(MAX_MESSAGE)
        .on_upgrade(move |socket| async move {
            // Keep upgraded sessions in the shutdown barrier as well as normal HTTP requests.
            let result = stream(
                socket,
                &endpoint,
                profile,
                &cfg.reverse_sink,
                server.cancel.clone(),
                if endpoint == "listen" {
                    &server.capture_program
                } else {
                    &server.playback_program
                },
            )
            .await;
            if let Err(e) = result {
                eprintln!("Sesión {endpoint}: {e:#}");
            }
            drop(task);
            drop(permit);
        })
}
async fn stream(
    mut socket: WebSocket,
    direction: &str,
    profile: Profile,
    sink: &str,
    cancel: CancellationToken,
    program: &std::path::Path,
) -> Result<()> {
    let (capture, playback, chunk_size) = profile.audio();
    let listening = direction == "listen";
    let mut cmd = Command::new(program);
    cmd.args(["--format=s16le", "--rate=48000", "--raw"]);
    if listening {
        cmd.args([
            "--device=redmi_phone.monitor",
            "--channels=2",
            &format!("--latency-msec={capture}"),
        ])
        .stdout(Stdio::piped())
        .stdin(Stdio::null());
    } else {
        cmd.args([
            "--playback",
            &format!("--device={sink}"),
            "--channels=1",
            &format!("--latency-msec={playback}"),
        ])
        .stdin(Stdio::piped())
        .stdout(Stdio::null());
    }
    let mut child = cmd
        .stderr(Stdio::inherit())
        .kill_on_drop(true)
        .spawn()
        .context("No se pudo abrir el audio")?;
    let mut input = child.stdin.take();
    let mut output = child.stdout.take();
    let mut buffer = vec![0; chunk_size];
    let mut filled = 0;
    let mut heartbeat = interval(Duration::from_secs(10));
    let mut last_seen = Instant::now();
    let result = async {
        loop {
            tokio::select! {
                biased;
                _ = cancel.cancelled() => break,
                status = child.wait() => { bail!("El proceso de audio terminó: {}", status?); }
                message = socket.next() => {
                    match message {
                        Some(Ok(Message::Close(_))) | None => break,
                        Some(Ok(Message::Ping(_))) => {
                            last_seen = Instant::now();
                            timeout(WRITE_TIMEOUT, socket.flush()).await??;
                        }
                        Some(Ok(Message::Pong(_))) => last_seen = Instant::now(),
                        Some(Ok(Message::Binary(data))) if !listening => {
                            if data.len() % 2 != 0 { bail!("PCM mono s16le incompleto"); }
                            last_seen = Instant::now();
                            timeout(WRITE_TIMEOUT, input.as_mut().unwrap().write_all(&data)).await
                                .context("La salida de audio no responde")??;
                        }
                        Some(Ok(_)) => {},
                        Some(Err(e)) => return Err(e.into()),
                    }
                }
                count = async { output.as_mut().unwrap().read(&mut buffer[filled..]).await }, if listening => {
                    let count = count?;
                    if count == 0 { bail!("La captura de audio terminó"); }
                    filled += count;
                    if filled == chunk_size {
                        timeout(WRITE_TIMEOUT, socket.send(Message::Binary(buffer.clone().into()))).await
                            .context("El celular no recibe audio a tiempo")??;
                        filled = 0;
                    }
                }
                _ = heartbeat.tick() => {
                    if last_seen.elapsed() > PEER_TIMEOUT { bail!("El celular dejó de responder"); }
                    timeout(WRITE_TIMEOUT, socket.send(Message::Ping(Vec::new().into()))).await??;
                }
            }
        }
        Ok(())
    }.await;
    // Every exit path releases the audio process, including stalled sockets and shutdown.
    let _ = child.kill().await;
    let _ = child.wait().await;
    let _ = timeout(Duration::from_millis(300), socket.close()).await;
    result
}
pub async fn run() -> Result<()> {
    let state = State::load()?;
    let server = Server::new(state.clone());
    let address = std::env::var("REEXAUDIO_LISTEN").unwrap_or_else(|_| "0.0.0.0:53317".into());
    let listener = tokio::net::TcpListener::bind(&address)
        .await
        .context("El puerto del servidor está ocupado o no está disponible")?;
    let bound = listener.local_addr()?;
    let mut terminate = tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())?;
    let routing = Routing::start(&state).await?;
    let cancel = server.cancel.clone();
    let stopped = cancel.clone();
    let app = server.router();
    let mut http = tokio::spawn(async move {
        axum::serve(listener, app)
            .with_graceful_shutdown(stopped.cancelled_owned())
            .await
    });
    let result = async {
        crate::system::notify_ready()?;
        eprintln!("ReExAudio {VERSION}: servidor listo en {bound}");
        tokio::select! {
            _ = tokio::signal::ctrl_c() => {},
            _ = terminate.recv() => {},
            result = &mut http => { result??; }
        }
        Ok::<_, anyhow::Error>(())
    }
    .await;
    cancel.cancel();
    // A slow APK download must not prevent restoration of the PC audio route.
    if !http.is_finished() && timeout(Duration::from_secs(3), &mut http).await.is_err() {
        http.abort();
        let _ = http.await;
    }
    server.tasks.close();
    server.tasks.wait().await;
    let restored = routing.restore(&state).await;
    result?;
    restored
}
