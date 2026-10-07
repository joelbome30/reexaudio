//! Run the real service with fake audio commands and an ephemeral port.
use reexaudio::config::State;
use serde_json::{Value, json};
use std::{os::unix::fs::PermissionsExt, process::Stdio, time::Duration};
use tokio::{
    io::{AsyncBufReadExt, BufReader},
    process::{Child, Command},
    time::timeout,
};

const PACTL: &str = r#"#!/usr/bin/python3
import json, os, sys
from pathlib import Path
path = Path(os.environ['REEXAUDIO_STATE_DIR']) / 'fake-audio.json'
s = json.loads(path.read_text())
a = sys.argv[1:]
if a[:3] == ['list', 'short', 'sinks']:
    print('1\tphysical\tdriver\tformat\tRUNNING')
    if s['virtual']: print('2\tredmi_phone\tdriver\tformat\tRUNNING')
elif a == ['get-default-sink']: print(s['default'])
elif a[0] == 'load-module': s['virtual'] = True; print('42')
elif a[0] == 'set-sink-volume': pass
elif a[0] == 'set-default-sink':
    if os.environ.get('FAIL_ROUTE') and a[1] == 'redmi_phone': sys.exit(1)
    s['default'] = a[1]
elif a[:3] == ['list', 'short', 'sink-inputs']: print('5\t' + s['stream'] + '\tclient')
elif a[0] == 'move-sink-input': s['stream'] = '2' if a[2] == 'redmi_phone' else '1'
elif a[:3] == ['list', 'short', 'modules']:
    if s['virtual']: print('42\tmodule-null-sink\tsink_name=redmi_phone\t1')
elif a[0] == 'unload-module': s['virtual'] = False
else: raise RuntimeError(a)
path.write_text(json.dumps(s))
"#;
fn setup() -> tempfile::TempDir {
    let dir = tempfile::tempdir().unwrap();
    let state = State::at(dir.path().into()).unwrap();
    state.save(&state.config().unwrap()).unwrap();
    std::fs::write(
        dir.path().join("fake-audio.json"),
        json!({"default":"physical","virtual":false,"stream":"1"}).to_string(),
    )
    .unwrap();
    std::fs::write(dir.path().join("pactl"), PACTL).unwrap();
    std::fs::set_permissions(
        dir.path().join("pactl"),
        std::fs::Permissions::from_mode(0o755),
    )
    .unwrap();
    dir
}
fn command(dir: &tempfile::TempDir) -> Command {
    let mut cmd = Command::new(env!("CARGO_BIN_EXE_reexaudio-server"));
    cmd.env("REEXAUDIO_STATE_DIR", dir.path())
        .env("REEXAUDIO_LISTEN", "127.0.0.1:0")
        .env_remove("NOTIFY_SOCKET")
        .env("PATH", format!("{}:/usr/bin:/bin", dir.path().display()))
        .stderr(Stdio::piped())
        .kill_on_drop(true);
    cmd
}
async fn start(dir: &tempfile::TempDir) -> Child {
    let mut child = command(dir).spawn().unwrap();
    let mut lines = BufReader::new(child.stderr.take().unwrap()).lines();
    timeout(Duration::from_secs(8), async {
        loop {
            let line = lines
                .next_line()
                .await
                .unwrap()
                .expect("server failed before ready");
            if line.contains("servidor listo") {
                break;
            }
        }
    })
    .await
    .unwrap();
    child
}
async fn stop(mut child: Child) {
    let status = Command::new("kill")
        .args(["-TERM", &child.id().unwrap().to_string()])
        .status()
        .await
        .unwrap();
    assert!(status.success());
    assert!(
        timeout(Duration::from_secs(8), child.wait())
            .await
            .unwrap()
            .unwrap()
            .success()
    );
}
fn audio(dir: &tempfile::TempDir) -> Value {
    serde_json::from_slice(&std::fs::read(dir.path().join("fake-audio.json")).unwrap()).unwrap()
}
#[tokio::test]
async fn stop_restores_audio_and_crash_is_recovered_on_restart() {
    let dir = setup();
    let mut child = start(&dir).await;
    assert_eq!(audio(&dir)["default"], "redmi_phone");
    assert_eq!(audio(&dir)["stream"], "2");
    // SIGKILL deliberately bypasses normal cleanup; the journal survives.
    child.kill().await.unwrap();
    child.wait().await.unwrap();
    assert!(dir.path().join("routing.json").exists());
    let child = start(&dir).await;
    stop(child).await;
    assert_eq!(
        audio(&dir),
        json!({"default":"physical","virtual":false,"stream":"1"})
    );
    assert!(!dir.path().join("routing.json").exists());
}
#[tokio::test]
async fn failed_start_rolls_back_the_created_sink() {
    let dir = setup();
    let mut cmd = command(&dir);
    cmd.env("FAIL_ROUTE", "1");
    let result = timeout(Duration::from_secs(8), cmd.output())
        .await
        .unwrap()
        .unwrap();
    assert!(!result.status.success());
    assert_eq!(
        audio(&dir),
        json!({"default":"physical","virtual":false,"stream":"1"})
    );
    assert!(!dir.path().join("routing.json").exists());
}
