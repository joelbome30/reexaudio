//! Wi-Fi Direct discovery through D-Bus, activation through NetworkManager,
//! and the Android QR-token handshake. No Python process is involved.
use anyhow::{Context, Result, bail, ensure};
use std::{
    collections::HashMap,
    net::{Ipv4Addr, SocketAddr},
    path::PathBuf,
    time::Duration,
};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::TcpStream,
    time::{sleep, timeout},
};
use zbus::{
    Connection, Proxy,
    zvariant::{OwnedObjectPath, Value},
};

const NM: &str = "org.freedesktop.NetworkManager";
const P2P: &str = "org.freedesktop.NetworkManager.Device.WifiP2P";
const PROFILE: &str = "redmi-audio-p2p";

pub struct WifiDirect {
    nmcli: PathBuf,
}

impl Default for WifiDirect {
    fn default() -> Self {
        Self::with_nmcli("nmcli".into())
    }
}

impl WifiDirect {
    /// Select the NetworkManager executable (also allows isolated process tests).
    pub fn with_nmcli(nmcli: PathBuf) -> Self {
        Self { nmcli }
    }

    async fn command(&self, args: &[&str]) -> Result<String> {
        crate::system::command(self.nmcli.to_str().context("Ruta nmcli inválida")?, args).await
    }

    pub async fn device(&self) -> Result<String> {
        let devices = self
            .command(&["-t", "-f", "DEVICE,TYPE", "device", "status"])
            .await?;
        devices
            .lines()
            .find_map(|line| {
                let (device, kind) = line.split_once(':')?;
                (kind == "wifi-p2p").then(|| device.to_owned())
            })
            .context("NetworkManager no detecta Wi-Fi Direct")
    }

    pub async fn discover(&self) -> Result<Vec<(String, String)>> {
        let device = self.device().await?;
        let path = self
            .command(&["-g", "GENERAL.DBUS-PATH", "device", "show", &device])
            .await?;
        timeout(Duration::from_secs(30), async {
            let bus = Connection::system().await?;
            let proxy = Proxy::new(&bus, NM, path.as_str(), P2P).await?;
            let options = HashMap::from([("timeout", Value::I32(30))]);
            let find: zbus::Result<()> = proxy.call("StartFind", &(options,)).await;
            if let Err(error) = find {
                if !error.to_string().to_ascii_lowercase().contains("busy") {
                    return Err(error.into());
                }
            }
            sleep(Duration::from_secs(6)).await;
            let peers: Vec<OwnedObjectPath> = proxy.get_property("Peers").await?;
            let mut found = Vec::new();
            for path in peers {
                let peer = Proxy::new(&bus, NM, path, "org.freedesktop.NetworkManager.WifiP2PPeer")
                    .await?;
                let name: String = peer.get_property("Name").await?;
                let address: String = peer.get_property("HwAddress").await?;
                if !address.is_empty() {
                    found.push((
                        if name.is_empty() {
                            "Dispositivo sin nombre".into()
                        } else {
                            name
                        },
                        address,
                    ));
                }
            }
            Ok(found)
        })
        .await
        .context("La búsqueda Wi-Fi Direct agotó el tiempo")?
    }

    pub async fn address(&self) -> Result<String> {
        let device = self.device().await?;
        let address = self
            .command(&["-g", "GENERAL.HWADDR", "device", "show", &device])
            .await?;
        if valid_address(&address) && address != "00:00:00:00:00:00" {
            return Ok(address.to_ascii_uppercase());
        }
        // NM may expose an empty address for its virtual P2P management device.
        // Read the actual P2P-device address on the same radio, not the station MAC.
        let details = crate::system::command("iw", &["dev"]).await?;
        address_from_iw(&details, device.strip_prefix("p2p-dev-").unwrap_or(&device))
    }

    pub async fn connect(&self, address: &str, token: &str) -> Result<()> {
        ensure!(valid_address(address), "Dirección Wi-Fi Direct inválida");
        validate_token(token)?;
        let device = self.device().await?;
        let _ = self.command(&["connection", "delete", PROFILE]).await;
        let result = async {
            self.command(&[
                "connection",
                "add",
                "type",
                "wifi-p2p",
                "ifname",
                &device,
                "con-name",
                PROFILE,
                "connection.autoconnect",
                "no",
                "wifi-p2p.peer",
                address,
                "wifi-p2p.wps-method",
                "pbc",
                "ipv4.method",
                "auto",
                "ipv4.never-default",
                "yes",
                "ipv6.method",
                "disabled",
            ])
            .await?;
            self.command(&[
                "--wait",
                "60",
                "connection",
                "up",
                PROFILE,
                "ifname",
                &device,
            ])
            .await?;
            let gateway = self
                .command(&["-g", "IP4.GATEWAY", "device", "show", &device])
                .await?;
            let ip = if gateway.is_empty() {
                let address = self
                    .command(&["-g", "IP4.ADDRESS", "device", "show", &device])
                    .await?;
                gateway_from_cidr(address.lines().next().unwrap_or_default())?
            } else {
                gateway
                    .parse::<Ipv4Addr>()
                    .context("Puerta de enlace Wi-Fi Direct inválida")?
            };
            confirm_peer(SocketAddr::from((ip, 53318)), token).await
        }
        .await;
        if let Err(error) = result {
            // Capture the original state before deleting the failed connection:
            // otherwise NetworkManager reports only "connection-removed".
            let reason = self
                .command(&[
                    "-g",
                    "GENERAL.STATE,GENERAL.REASON",
                    "device",
                    "show",
                    &device,
                ])
                .await
                .unwrap_or_default();
            let _ = self.command(&["connection", "delete", PROFILE]).await;
            return Err(error).with_context(|| {
                format!(
                    "No se pudo enlazar con el dispositivo Wi-Fi Direct. \
                 Deja abierta la app del celular y acepta la solicitud si aparece. \
                 NetworkManager: {}",
                    reason.replace('\n', " · ")
                )
            });
        }
        Ok(())
    }
}

fn valid_address(address: &str) -> bool {
    let parts: Vec<_> = address.split(':').collect();
    parts.len() == 6
        && parts
            .iter()
            .all(|part| part.len() == 2 && part.bytes().all(|b| b.is_ascii_hexdigit()))
}

fn address_from_iw(details: &str, parent: &str) -> Result<String> {
    let marker = format!("Interface {parent}");
    for radio in details.split("phy#") {
        if !radio.lines().any(|line| line.trim() == marker) {
            continue;
        }
        let mut address = "";
        for line in radio.lines().map(str::trim) {
            if let Some(value) = line.strip_prefix("addr ") {
                address = value;
            }
            if line == "type P2P-device" && valid_address(address) {
                return Ok(address.to_ascii_uppercase());
            }
        }
    }
    bail!("No se pudo leer la dirección Wi-Fi Direct del PC")
}

fn validate_token(token: &str) -> Result<()> {
    ensure!(
        !token.is_empty() && !token.contains(['\r', '\n']),
        "Token de emparejamiento inválido"
    );
    Ok(())
}

fn gateway_from_cidr(cidr: &str) -> Result<Ipv4Addr> {
    let (ip, prefix) = cidr
        .split_once('/')
        .context("Wi-Fi Direct no obtuvo una dirección IP")?;
    let ip = u32::from(ip.parse::<Ipv4Addr>()?);
    let prefix = prefix.parse::<u32>()?;
    ensure!(
        prefix <= 30,
        "La subred Wi-Fi Direct no tiene una puerta de enlace válida"
    );
    let mask = u32::MAX.checked_shl(32 - prefix).unwrap_or(0);
    Ok(Ipv4Addr::from((ip & mask) + 1))
}

/// Authenticate against the phone's pairing listener before starting PC audio.
pub async fn confirm_peer(address: SocketAddr, token: &str) -> Result<()> {
    validate_token(token)?;
    let mut peer = timeout(Duration::from_secs(10), TcpStream::connect(address))
        .await
        .context("El celular no respondió al emparejamiento")??;
    timeout(Duration::from_secs(5), async {
        peer.write_all(format!("{token}\n").as_bytes()).await?;
        let mut response = [0; 3];
        peer.read_exact(&mut response).await?;
        if &response != b"OK\n" {
            bail!("El celular no confirmó la conexión");
        }
        Ok(())
    })
    .await
    .context("El celular no confirmó la conexión a tiempo")?
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn qr_uses_p2p_address_on_selected_radio() {
        let details = "phy#1\nUnnamed/non-netdev interface\naddr 02:00:00:00:00:11\ntype P2P-device\nInterface wlan1\naddr 02:00:00:00:00:12\ntype managed\nphy#0\nUnnamed/non-netdev interface\naddr 02:00:00:00:00:21\ntype P2P-device\nInterface wlan0\naddr 02:00:00:00:00:22\ntype managed\n";
        assert_eq!(
            address_from_iw(details, "wlan0").unwrap(),
            "02:00:00:00:00:21"
        );
        assert_eq!(
            address_from_iw(details, "wlan1").unwrap(),
            "02:00:00:00:00:11"
        );
        assert!(address_from_iw(details, "wlan2").is_err());
    }

    #[test]
    fn gateway_uses_actual_subnet() {
        assert_eq!(
            gateway_from_cidr("192.168.49.12/24").unwrap(),
            Ipv4Addr::new(192, 168, 49, 1)
        );
        assert_eq!(
            gateway_from_cidr("10.10.6.20/23").unwrap(),
            Ipv4Addr::new(10, 10, 6, 1)
        );
        for invalid in ["", "10.0.0.2", "10.0.0.2/33", "10.0.0.2/32"] {
            assert!(gateway_from_cidr(invalid).is_err());
        }
    }
}
