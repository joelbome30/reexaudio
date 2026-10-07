//! Own audio routing in the service, so closing the window cannot strand PC audio.
use crate::{
    VIRTUAL,
    config::{State, atomic_json},
    system::{pactl, sinks},
};
use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};

#[derive(Debug, Serialize, Deserialize)]
pub struct Routing {
    previous: String,
    module: Option<String>,
}
impl Routing {
    pub async fn start(state: &State) -> Result<Self> {
        let path = state.dir.join("routing.json");
        if path.exists() {
            let stale: Self = serde_json::from_slice(&std::fs::read(&path)?)?;
            stale
                .restore(state)
                .await
                .context("No se pudo recuperar la salida de audio anterior")?;
        }
        let cfg = state.config()?;
        let physical = sinks().await?;
        let current = pactl(&["get-default-sink"]).await?;
        let previous = if current == VIRTUAL {
            cfg.extra
                .get("previous_sink")
                .and_then(|v| v.as_str())
                .filter(|name| physical.iter().any(|(_, n)| n == name && n != VIRTUAL))
                .map(str::to_owned)
                .or_else(|| {
                    physical
                        .iter()
                        .find(|(_, n)| n != VIRTUAL)
                        .map(|(_, n)| n.clone())
                })
                .unwrap_or_default()
        } else {
            current
        };
        let mut routing = Self {
            previous,
            module: None,
        };
        atomic_json(&path, &routing)?;
        let setup = async {
            if !physical.iter().any(|(_, name)| name == VIRTUAL) {
                routing.module = Some(
                    pactl(&[
                        "load-module",
                        "module-null-sink",
                        "sink_name=redmi_phone",
                        "sink_properties=device.description=ReExAudio",
                    ])
                    .await?,
                );
                atomic_json(&path, &routing)?;
            }
            set_volume(cfg.volume).await?;
            if cfg.pc_audio {
                pactl(&["set-default-sink", VIRTUAL]).await?;
                for line in pactl(&["list", "short", "sink-inputs"]).await?.lines() {
                    if let Some(id) = line.split('\t').next() {
                        // A stream can disappear between enumeration and this call.
                        let _ = pactl(&["move-sink-input", id, VIRTUAL]).await;
                    }
                }
            }
            Ok::<_, anyhow::Error>(())
        }
        .await;
        if let Err(error) = setup {
            if let Err(restore) = routing.restore(state).await {
                eprintln!("Recuperación de audio: {restore:#}");
            }
            return Err(error);
        }
        Ok(routing)
    }
    pub async fn restore(&self, state: &State) -> Result<()> {
        let available = sinks().await?;
        let current = pactl(&["get-default-sink"]).await?;
        let target = available
            .iter()
            .find(|(_, n)| n == &self.previous && n != VIRTUAL)
            .or_else(|| {
                available
                    .iter()
                    .find(|(_, n)| n == &current && n != VIRTUAL)
            })
            .or_else(|| available.iter().find(|(_, n)| n != VIRTUAL));
        if let Some((_, target)) = target {
            if current == VIRTUAL {
                pactl(&["set-default-sink", target]).await?;
            }
            if let Some((virtual_id, _)) = available.iter().find(|(_, n)| n == VIRTUAL) {
                for line in pactl(&["list", "short", "sink-inputs"]).await?.lines() {
                    let cols: Vec<_> = line.split('\t').collect();
                    if cols.get(1) == Some(&virtual_id.as_str()) {
                        let _ = pactl(&["move-sink-input", cols[0], target]).await;
                    }
                }
            }
        }
        if let Some(module) = &self.module {
            // Only unload our module when the ID still belongs to this virtual sink.
            let modules = pactl(&["list", "short", "modules"]).await?;
            if modules.lines().any(|line| {
                let cols: Vec<_> = line.split('\t').collect();
                cols.first() == Some(&module.as_str())
                    && cols.get(1) == Some(&"module-null-sink")
                    && cols.get(2).is_some_and(|args| {
                        args.split_whitespace()
                            .any(|s| s == "sink_name=redmi_phone")
                    })
            }) {
                pactl(&["unload-module", module]).await?;
            }
        }
        match std::fs::remove_file(state.dir.join("routing.json")) {
            Err(e) if e.kind() != std::io::ErrorKind::NotFound => return Err(e.into()),
            _ => {}
        }
        Ok(())
    }
}
pub async fn set_volume(volume: u16) -> Result<()> {
    pactl(&["set-sink-volume", VIRTUAL, &format!("{}%", volume.min(150))]).await?;
    Ok(())
}
