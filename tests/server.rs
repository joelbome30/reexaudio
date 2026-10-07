use futures_util::{SinkExt, StreamExt};
use reexaudio::{
    config::{Profile, State},
    server::Server,
};
use std::{os::unix::fs::PermissionsExt, time::Duration};
use tempfile::TempDir;
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::TcpStream,
    time::timeout,
};
use tokio_tungstenite::{
    connect_async,
    tungstenite::{Error, Message},
};

fn script(dir: &TempDir, name: &str, body: &str) -> std::path::PathBuf {
    let path = dir.path().join(name);
    std::fs::write(&path, format!("#!/usr/bin/python3\n{body}\n")).unwrap();
    std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o755)).unwrap();
    path
}
async fn fixture() -> (TempDir, Server, String, tokio::task::JoinHandle<()>) {
    let dir = tempfile::tempdir().unwrap();
    let state = State::at(dir.path().join("state")).unwrap();
    let mut cfg = state.config().unwrap();
    cfg.reverse_sink = "physical-test".into();
    state.save(&cfg).unwrap();
    let mut server = Server::new(state);
    server.apk = dir.path().join("app.apk");
    std::fs::write(&server.apk, b"fake-apk-download").unwrap();
    server.capture_program = script(
        &dir,
        "capture",
        &format!(
            r#"
import os, time, sys
open({:?}, 'w').write(str(os.getpid()))
open({:?}, 'w').write(' '.join(sys.argv[1:]))
while True:
    os.write(1, b'\x01\x02\x03\x04' * 1920)
    time.sleep(0.005)
"#,
            dir.path().join("capture.pid"),
            dir.path().join("capture.args")
        ),
    );
    server.playback_program = script(
        &dir,
        "playback",
        &format!(
            r#"
import os, sys
open({:?}, 'w').write(str(os.getpid()))
open({:?}, 'w').write(' '.join(sys.argv[1:]))
with open({:?}, 'wb', buffering=0) as target:
    while True:
        data = os.read(0, 4096)
        if not data: break
        target.write(data)
"#,
            dir.path().join("playback.pid"),
            dir.path().join("playback.args"),
            dir.path().join("received.pcm")
        ),
    );
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap().to_string();
    let app = server.router();
    let cancel = server.cancel.clone();
    let task = tokio::spawn(async move {
        axum::serve(listener, app)
            .with_graceful_shutdown(cancel.cancelled_owned())
            .await
            .unwrap();
    });
    (dir, server, address, task)
}
async fn get(address: &str, path: &str) -> Vec<u8> {
    let mut stream = TcpStream::connect(address).await.unwrap();
    stream
        .write_all(
            format!("GET {path} HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                .as_bytes(),
        )
        .await
        .unwrap();
    let mut data = Vec::new();
    timeout(Duration::from_secs(3), stream.read_to_end(&mut data))
        .await
        .unwrap()
        .unwrap();
    data
}
async fn shutdown(server: Server, task: tokio::task::JoinHandle<()>) {
    server.cancel.cancel();
    server.tasks.close();
    timeout(Duration::from_secs(4), server.tasks.wait())
        .await
        .unwrap();
    timeout(Duration::from_secs(4), task)
        .await
        .unwrap()
        .unwrap();
}
async fn wait_file(path: &std::path::Path) -> Vec<u8> {
    timeout(Duration::from_secs(3), async {
        loop {
            if let Ok(data) = std::fs::read(path)
                && !data.is_empty()
            {
                return data;
            }
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
    })
    .await
    .unwrap()
}
async fn assert_reaped(path: &std::path::Path) {
    let pid = String::from_utf8(wait_file(path).await).unwrap();
    timeout(Duration::from_secs(3), async {
        while std::path::Path::new(&format!("/proc/{}", pid.trim())).exists() {
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
    })
    .await
    .expect("audio process must be reaped");
}
#[tokio::test]
async fn http_pairing_auth_and_both_apk_names() {
    let (_dir, server, address, task) = fixture().await;
    let token = &server.state.token;
    for route in [
        "/bad".into(),
        "/bad/listen".into(),
        format!("/{token}/unknown"),
    ] {
        assert!(get(&address, &route).await.starts_with(b"HTTP/1.1 404"));
    }
    let html = get(&address, &format!("/{token}")).await;
    assert!(html.starts_with(b"HTTP/1.1 200"));
    assert!(String::from_utf8_lossy(&html).contains("Descargar ReExAudio"));
    for name in [
        "app.apk".into(),
        format!("ReExAudio-{}.apk", reexaudio::VERSION),
    ] {
        let response = get(&address, &format!("/{token}/{name}")).await;
        assert!(response.starts_with(b"HTTP/1.1 200"));
        assert!(response.ends_with(b"fake-apk-download"));
    }
    assert!(
        get(&address, &format!("/{token}/listen"))
            .await
            .starts_with(b"HTTP/1.1 400")
    );
    shutdown(server, task).await;
}
#[tokio::test]
async fn all_profiles_stream_pcm_and_answer_ping() {
    for profile in [Profile::Performance, Profile::Balanced, Profile::Quality] {
        let (dir, server, address, task) = fixture().await;
        let url = format!(
            "ws://{address}/{}/listen?profile={}",
            server.state.token,
            profile.name()
        );
        let (mut socket, _) = connect_async(url).await.unwrap();
        socket
            .send(Message::Ping(b"test".to_vec().into()))
            .await
            .unwrap();
        let mut audio = false;
        let mut pong = false;
        timeout(Duration::from_secs(3), async {
            while !(audio && pong) {
                match socket.next().await.unwrap().unwrap() {
                    Message::Binary(bytes) => {
                        assert_eq!(bytes.len(), profile.audio().2);
                        assert_eq!(&bytes[..4], &[1, 2, 3, 4]);
                        audio = true;
                    }
                    Message::Pong(bytes) if bytes.as_ref() == b"test" => pong = true,
                    _ => {
                        socket.flush().await.unwrap();
                    }
                }
            }
        })
        .await
        .unwrap();
        let args = String::from_utf8(wait_file(&dir.path().join("capture.args")).await).unwrap();
        assert!(args.contains(&format!("--latency-msec={}", profile.audio().0)));
        assert!(args.contains("--channels=2"));
        socket.close(None).await.unwrap();
        while let Some(Ok(_)) = socket.next().await {}
        assert_reaped(&dir.path().join("capture.pid")).await;
        shutdown(server, task).await;
    }
}
#[tokio::test]
async fn phone_audio_is_exact_and_process_exits_on_shutdown() {
    let (dir, server, address, task) = fixture().await;
    let (mut socket, _) = connect_async(format!(
        "ws://{address}/{}/send?profile=quality",
        server.state.token
    ))
    .await
    .unwrap();
    let pcm: Vec<u8> = (0..1920).map(|n| (n % 255) as u8).collect();
    // An RFC 6455 message may split in the middle of a PCM sample.
    use tokio_tungstenite::tungstenite::protocol::frame::{
        Frame,
        coding::{Data, OpCode},
    };
    socket
        .send(Message::Frame(Frame::message(
            pcm[..1001].to_vec(),
            OpCode::Data(Data::Binary),
            false,
        )))
        .await
        .unwrap();
    socket
        .send(Message::Frame(Frame::message(
            pcm[1001..].to_vec(),
            OpCode::Data(Data::Continue),
            true,
        )))
        .await
        .unwrap();
    assert_eq!(wait_file(&dir.path().join("received.pcm")).await, pcm);
    let args = String::from_utf8(wait_file(&dir.path().join("playback.args")).await).unwrap();
    assert!(args.contains("--device=physical-test"));
    assert!(args.contains("--channels=1"));
    assert!(args.contains("--latency-msec=80"));
    shutdown(server, task).await;
    assert_reaped(&dir.path().join("playback.pid")).await;
}
#[tokio::test]
async fn missing_output_and_wrong_token_reject_before_upgrade() {
    let (_dir, server, address, task) = fixture().await;
    let mut config = server.state.config().unwrap();
    config.reverse_sink.clear();
    server.state.save(&config).unwrap();
    for (path, status) in [
        (format!("/{}/send", server.state.token), 409),
        ("/invalid/listen".into(), 404),
    ] {
        let result = connect_async(format!("ws://{address}{path}")).await;
        assert!(
            matches!(result, Err(Error::Http(response)) if response.status().as_u16() == status)
        );
    }
    shutdown(server, task).await;
}
#[test]
fn legacy_settings_and_token_survive_migration() {
    let dir = tempfile::tempdir().unwrap();
    std::fs::write(dir.path().join("token"), "legacy_AbC-123\n").unwrap();
    std::fs::write(dir.path().join("config.json"), r#"{"previous_sink":"speakers","reverse_sink":"headphones","volume":130,"connection":"local","pc_audio":false,"profile":"quality"}"#).unwrap();
    let state = State::at(dir.path().into()).unwrap();
    let cfg = state.config().unwrap();
    assert_eq!(state.token, "legacy_AbC-123");
    assert_eq!(cfg.profile, Profile::Quality);
    assert!(!cfg.pc_audio);
    state.save(&cfg).unwrap();
    let cfg = state.config().unwrap();
    assert_eq!(cfg.extra["previous_sink"], "speakers");
    assert_eq!(cfg.reverse_sink, "headphones");
    assert_eq!(cfg.volume, 130);
    assert!(cfg.dark);
    assert_eq!(State::at(dir.path().into()).unwrap().token, state.token);
}
