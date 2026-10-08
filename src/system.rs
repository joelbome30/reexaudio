use anyhow::{Context, Result, bail};
use std::{process::Stdio, time::Duration};
use tokio::{process::Command, time::timeout};

pub async fn command(program: &str, args: &[&str]) -> Result<String> {
    let result = timeout(
        Duration::from_secs(65),
        Command::new(program)
            .args(args)
            .env("LC_ALL", "C")
            .stdin(Stdio::null())
            .kill_on_drop(true)
            .output(),
    )
    .await
    .with_context(|| format!("{program}: tiempo agotado"))?
    .with_context(|| format!("No se pudo ejecutar {program}"))?;
    if !result.status.success() {
        bail!(
            "{program}: {}",
            String::from_utf8_lossy(&result.stderr).trim()
        );
    }
    Ok(String::from_utf8(result.stdout)?.trim().to_owned())
}
pub async fn pactl(args: &[&str]) -> Result<String> {
    command("pactl", args).await
}
pub async fn sinks() -> Result<Vec<(String, String)>> {
    Ok(pactl(&["list", "short", "sinks"])
        .await?
        .lines()
        .filter_map(|s| {
            let mut parts = s.split('\t');
            Some((parts.next()?.into(), parts.next()?.into()))
        })
        .collect())
}
pub async fn service_state() -> Result<String> {
    command(
        "systemctl",
        &[
            "--user",
            "show",
            "--property=ActiveState",
            "--value",
            crate::SERVICE,
        ],
    )
    .await
}

pub fn notify_ready() -> Result<()> {
    use std::os::unix::ffi::OsStrExt;
    use std::os::{
        linux::net::SocketAddrExt,
        unix::net::{SocketAddr, UnixDatagram},
    };
    if let Some(path) = std::env::var_os("NOTIFY_SOCKET") {
        let bytes = path.as_bytes();
        let addr = if bytes.first() == Some(&b'@') {
            SocketAddr::from_abstract_name(&bytes[1..])?
        } else {
            SocketAddr::from_pathname(path)?
        };
        UnixDatagram::unbound()?.send_to_addr(b"READY=1", &addr)?;
    }
    Ok(())
}
