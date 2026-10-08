use reexaudio::p2p::{WifiDirect, confirm_peer};
use std::os::unix::fs::PermissionsExt;
use tokio::{
    io::{AsyncBufReadExt, AsyncWriteExt, BufReader},
    net::TcpListener,
};

#[tokio::test]
#[ignore = "requires NetworkManager and a local Wi-Fi Direct adapter"]
async fn discovers_peers_on_local_adapter() {
    let address = WifiDirect::default().address().await.unwrap();
    assert_eq!(address.len(), 17);
    let peers = WifiDirect::default().discover().await.unwrap();
    println!("Wi-Fi Direct discovery completed: {} peers", peers.len());
}

#[tokio::test]
async fn confirms_qr_token_over_tcp_with_fragmented_reply() {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap();
    let phone = tokio::spawn(async move {
        let (stream, _) = listener.accept().await.unwrap();
        let mut stream = BufReader::new(stream);
        let mut token = String::new();
        stream.read_line(&mut token).await.unwrap();
        assert_eq!(token, "example-token\n");
        stream.get_mut().write_all(b"O").await.unwrap();
        tokio::task::yield_now().await;
        stream.get_mut().write_all(b"K\n").await.unwrap();
    });
    confirm_peer(address, "example-token").await.unwrap();
    phone.await.unwrap();
}

#[tokio::test]
async fn rejects_negative_and_truncated_confirmations() {
    for response in [b"NO\n".as_slice(), b"OK".as_slice()] {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let phone = tokio::spawn(async move {
            let (mut stream, _) = listener.accept().await.unwrap();
            stream.write_all(response).await.unwrap();
            stream.shutdown().await.unwrap();
        });
        assert!(confirm_peer(address, "example-token").await.is_err());
        phone.await.unwrap();
    }
}

#[tokio::test]
async fn failed_activation_removes_networkmanager_profile() {
    let dir = tempfile::tempdir().unwrap();
    let program = dir.path().join("nmcli");
    // Only this fixture process sees the fake commands; the host network is untouched.
    std::fs::write(&program, r#"#!/bin/sh
printf '%s\n' "$*" >> "$(dirname "$0")/calls"
case "$*" in
  '-t -f DEVICE,TYPE device status') printf 'wlan0:wifi\np2p-dev-wlan0:wifi-p2p\n' ;;
  '--wait 60 connection up redmi-audio-p2p ifname p2p-dev-wlan0') echo 'activation failed' >&2; exit 1 ;;
  '-g GENERAL.STATE,GENERAL.REASON device show p2p-dev-wlan0') printf '50 (config)\n0 (No reason given)\n' ;;
esac
"#).unwrap();
    std::fs::set_permissions(&program, std::fs::Permissions::from_mode(0o755)).unwrap();
    let wifi = WifiDirect::with_nmcli(program);
    let error = wifi
        .connect("02:00:00:00:00:01", "example-token")
        .await
        .unwrap_err();
    assert!(format!("{error:#}").contains("activation failed"));
    assert!(format!("{error:#}").contains("50 (config)"));
    let calls = std::fs::read_to_string(dir.path().join("calls")).unwrap();
    assert!(calls.contains("wifi-p2p.peer 02:00:00:00:00:01"));
    assert_eq!(
        calls.lines().last(),
        Some("connection delete redmi-audio-p2p")
    );
    assert_eq!(
        calls.matches("connection delete redmi-audio-p2p").count(),
        2
    );
}

#[tokio::test]
async fn invalid_peer_is_rejected_before_running_networkmanager() {
    let wifi = WifiDirect::with_nmcli("/nonexistent/nmcli".into());
    for address in ["", "02:00:00:00:00", "GG:00:00:00:00:01"] {
        let error = wifi.connect(address, "example-token").await.unwrap_err();
        assert!(
            error
                .to_string()
                .contains("Dirección Wi-Fi Direct inválida")
        );
    }
}
