//! All I/O runs on one worker. Only event-loop callbacks touch Slint models.
use crate::{
    SERVICE, VERSION, VIRTUAL, audio,
    config::{Config, Profile, State, asset},
    system,
};
use anyhow::{Context, Result};
use slint::{ComponentHandle, ModelRc, SharedString, VecModel};
use std::{net::UdpSocket, time::Duration};
use tokio::sync::mpsc;
slint::include_modules!();

enum Action {
    Quit,
    Refresh,
    Devices,
    Pairing,
    Save(Config),
    AutoConnect,
    Toggle,
}
fn items(values: Vec<String>) -> ModelRc<MenuItem> {
    ModelRc::new(VecModel::from(
        values
            .into_iter()
            .map(|text| MenuItem {
                text: text.into(),
                enabled: true,
                ..Default::default()
            })
            .collect::<Vec<_>>(),
    ))
}
fn apply_theme(ui: &AppWindow) {
    let palette = ui.global::<MaterialPalette>();
    let mut schemes = palette.get_schemes();
    let colors = match ui.get_accent() {
        1 => [0xa8d5a2, 0x12380f, 0x2b5127, 0xc3efbc, 0x416b39, 0xffffff],
        2 => [0xd2bcff, 0x381e72, 0x4f378b, 0xeaddff, 0x6750a4, 0xffffff],
        _ => [0xadc6ff, 0x002e69, 0x284777, 0xd8e2ff, 0x445e91, 0xffffff],
    };
    let color =
        |rgb: u32| slint::Color::from_rgb_u8((rgb >> 16) as u8, (rgb >> 8) as u8, rgb as u8);
    schemes.dark.primary = color(colors[0]);
    schemes.dark.onPrimary = color(colors[1]);
    schemes.dark.primaryContainer = color(colors[2]);
    schemes.dark.onPrimaryContainer = color(colors[3]);
    schemes.light.primary = color(colors[4]);
    schemes.light.onPrimary = color(colors[5]);
    schemes.light.primaryContainer = color(colors[3]);
    schemes.light.onPrimaryContainer = color(colors[1]);
    palette.set_schemes(schemes);
}
fn settings(ui: &AppWindow, original: &Config) -> Config {
    use slint::Model;
    let mut cfg = original.clone();
    cfg.dark = ui.get_dark();
    cfg.accent = ui.get_accent() as u8;
    cfg.pc_audio = ui.get_pc_audio();
    cfg.volume = ui.get_volume().round().clamp(0., 150.) as u16;
    cfg.profile = match ui.get_profile() {
        0 => Profile::Performance,
        2 => Profile::Quality,
        _ => Profile::Balanced,
    };
    cfg.connection = if ui.get_direct() { "direct" } else { "local" }.into();
    cfg.reverse_sink = ui
        .get_outputs()
        .row_data(ui.get_output_index() as usize)
        .map(|item| item.text.to_string())
        .unwrap_or_default();
    cfg
}
fn send(ui: &AppWindow, sender: &mpsc::Sender<Action>, action: Action) {
    match sender.try_send(action) {
        Ok(()) => {
            ui.set_busy(true);
            ui.set_has_error(false);
            ui.set_feedback("Aplicando…".into());
        }
        Err(_) => {
            ui.set_has_error(true);
            ui.set_feedback("Hay una operación pendiente. Inténtalo de nuevo.".into());
        }
    }
}
pub fn run() -> Result<()> {
    let (state, cfg) = match State::load().and_then(|state| {
        let cfg = state.config()?;
        Ok((state, cfg))
    }) {
        Ok(loaded) => loaded,
        Err(error) => {
            let ui = AppWindow::new()?;
            ui.set_service_status("No se pudo abrir la configuración".into());
            ui.set_feedback(format!("{error:#}").into());
            ui.set_has_error(true);
            ui.set_version(VERSION.into());
            ui.run()?;
            return Ok(());
        }
    };
    let ui = AppWindow::new()?;
    ui.set_version(VERSION.into());
    ui.set_dark(cfg.dark);
    ui.set_accent(cfg.accent as i32);
    ui.set_direct(cfg.connection == "direct");
    ui.set_pc_audio(cfg.pc_audio);
    ui.set_volume(cfg.volume as f32);
    ui.set_profile(match cfg.profile {
        Profile::Performance => 0,
        Profile::Balanced => 1,
        Profile::Quality => 2,
    });
    apply_theme(&ui);
    let (sender, receiver) = mpsc::channel(16);
    let weak = ui.as_weak();
    let worker = std::thread::Builder::new()
        .name("reexaudio-control".into())
        .spawn(move || {
            let runtime = tokio::runtime::Builder::new_current_thread()
                .enable_all()
                .build()
                .expect("runtime");
            runtime.block_on(worker(state, receiver, weak));
        })?;
    macro_rules! action {
        ($register:ident, $action:expr) => {{
            let weak = ui.as_weak();
            let tx = sender.clone();
            ui.$register(move || {
                if let Some(ui) = weak.upgrade() {
                    send(&ui, &tx, $action);
                }
            });
        }};
    }
    let volume_timer = std::rc::Rc::new(slint::Timer::default());
    {
        let weak = ui.as_weak();
        let tx = sender.clone();
        let cfg = cfg.clone();
        let timer = volume_timer.clone();
        ui.on_volume_changed(move || {
            if weak.upgrade().is_none_or(|ui| ui.get_busy()) {
                return;
            }
            let weak = weak.clone();
            let tx = tx.clone();
            let cfg = cfg.clone();
            timer.start(
                slint::TimerMode::SingleShot,
                Duration::from_millis(200),
                move || {
                    if let Some(ui) = weak.upgrade() {
                        send(&ui, &tx, Action::Save(settings(&ui, &cfg)));
                    }
                },
            );
        });
    }
    action!(on_refresh_pairing, Action::Pairing);
    action!(on_refresh_devices, Action::Devices);
    action!(on_toggle, Action::Toggle);
    {
        let weak = ui.as_weak();
        let tx = sender.clone();
        let cfg = cfg.clone();
        ui.on_settings_changed(move || {
            if let Some(ui) = weak.upgrade() {
                send(&ui, &tx, Action::Save(settings(&ui, &cfg)));
            }
        });
    }
    {
        let weak = ui.as_weak();
        let tx = sender.clone();
        ui.on_appearance_changed(move || {
            if let Some(ui) = weak.upgrade() {
                apply_theme(&ui);
                send(&ui, &tx, Action::Save(settings(&ui, &cfg)));
            }
        });
    }
    {
        let weak = ui.as_weak();
        ui.on_copy_link(move || {
            if let Some(ui) = weak.upgrade() {
                ui.set_feedback("Enlace de emparejamiento copiado".into());
                ui.set_has_error(false);
            }
        });
    }
    let timer = slint::Timer::default();
    let p2p_timer = slint::Timer::default();
    {
        let weak = ui.as_weak();
        let tx = sender.clone();
        timer.start(
            slint::TimerMode::Repeated,
            Duration::from_secs(3),
            move || {
                if weak.upgrade().is_some_and(|ui| !ui.get_busy()) {
                    let _ = tx.try_send(Action::Refresh);
                }
            },
        );
    }
    {
        let weak = ui.as_weak();
        let tx = sender.clone();
        p2p_timer.start(
            slint::TimerMode::Repeated,
            Duration::from_secs(12),
            move || {
                if weak
                    .upgrade()
                    .is_some_and(|ui| ui.get_direct() && !ui.get_busy())
                {
                    if tx.try_send(Action::AutoConnect).is_ok() {
                        if let Some(ui) = weak.upgrade() {
                            ui.set_busy(true);
                        }
                    }
                }
            },
        );
    }
    sender.try_send(Action::Devices)?;
    sender.try_send(Action::Pairing)?;
    let result = ui.run();
    drop(timer);
    drop(p2p_timer);
    drop(volume_timer);
    // Finish an in-flight operation before dropping its runtime/process handles.
    // The window is already closed; the independent audio service keeps running.
    let _ = sender.blocking_send(Action::Quit);
    worker
        .join()
        .map_err(|_| anyhow::anyhow!("El controlador de escritorio terminó inesperadamente"))?;
    result?;
    Ok(())
}
async fn worker(state: State, mut receiver: mpsc::Receiver<Action>, ui: slint::Weak<AppWindow>) {
    // A package manager may have installed the user unit during this login session.
    let _ = system::command("systemctl", &["--user", "daemon-reload"]).await;
    while let Some(action) = receiver.recv().await {
        if matches!(action, Action::Quit) {
            break;
        }
        let polling = matches!(action, Action::Refresh | Action::AutoConnect);
        let clears_busy = matches!(&action, Action::AutoConnect);
        if !polling {
            let _ = ui.upgrade_in_event_loop(|ui| ui.set_busy(true));
        }
        let result = async {
            match action {
                Action::Quit => unreachable!(),
                Action::Refresh => refresh(&ui).await?,
                Action::Devices => {
                    let outputs: Vec<String> = system::sinks()
                        .await?
                        .into_iter()
                        .map(|(_, name)| name)
                        .filter(|name| name != VIRTUAL)
                        .collect();
                    let mut cfg = state.config()?;
                    let index = outputs
                        .iter()
                        .position(|name| name == &cfg.reverse_sink)
                        .unwrap_or(0);
                    cfg.reverse_sink = outputs.get(index).cloned().unwrap_or_default();
                    state.save(&cfg)?;
                    let selected = if outputs.is_empty() { -1 } else { index as i32 };
                    ui.upgrade_in_event_loop(move |ui| {
                        ui.set_outputs(items(outputs));
                        ui.set_output_index(selected);
                    })?;
                    refresh(&ui).await?;
                }
                Action::Pairing => pairing(&state, &ui).await?,
                Action::Save(cfg) => {
                    let previous = state.config()?;
                    let changed =
                        previous.connection != cfg.connection || previous.profile != cfg.profile;
                    state.save(&cfg)?;
                    if cfg.volume != previous.volume
                        && system::sinks().await?.iter().any(|(_, n)| n == VIRTUAL)
                    {
                        audio::set_volume(cfg.volume).await?;
                    }
                    if changed {
                        pairing(&state, &ui).await?;
                    }
                }
                Action::AutoConnect => {
                    let connected = serde_json::from_str::<bool>(&helper(&["connected"]).await?)
                        .unwrap_or(false);
                    let paired = if connected {
                        true
                    } else {
                        ui.upgrade_in_event_loop(|ui| {
                            ui.set_pairing_note(
                                "Esperando la conexión del celular que escaneó el QR…".into(),
                            )
                        })?;
                        serde_json::from_str::<bool>(&helper(&["listen"]).await?).unwrap_or(false)
                    };
                    if paired {
                        return_auto_status(&ui, "Celular conectado · Escanea el QR desde Android")
                            .await?;
                        system::command("systemctl", &["--user", "start", SERVICE]).await?;
                        refresh(&ui).await?;
                        ui.upgrade_in_event_loop(|ui| {
                            ui.set_pairing_note(
                                "Celular conectado. Ya puedes iniciar el audio desde Android."
                                    .into(),
                            )
                        })?;
                    }
                }
                Action::Toggle => {
                    let active = system::service_state().await?;
                    system::command(
                        "systemctl",
                        &[
                            "--user",
                            if active == "active" { "stop" } else { "start" },
                            SERVICE,
                        ],
                    )
                    .await?;
                    refresh(&ui).await?;
                }
            }
            Ok::<_, anyhow::Error>(())
        }
        .await;
        if !polling || clears_busy || result.is_err() {
            let error = result.err().map(|e| format!("{e:#}"));
            let _ = ui.upgrade_in_event_loop(move |ui| {
                ui.set_busy(false);
                ui.set_has_error(error.is_some());
                if let Some(error) = error {
                    ui.set_feedback(error.into());
                } else if !polling {
                    ui.set_feedback("Listo. Elige escuchar o enviar audio desde Android.".into());
                }
            });
        }
    }
}
async fn return_auto_status(ui: &slint::Weak<AppWindow>, message: &str) -> Result<()> {
    let message = message.to_string();
    ui.upgrade_in_event_loop(move |ui| ui.set_pairing_note(message.into()))?;
    Ok(())
}
async fn helper(args: &[&str]) -> Result<String> {
    let path = asset("p2p.py");
    let mut command = vec![path.to_str().context("Ruta P2P inválida")?];
    command.extend_from_slice(args);
    system::command("/usr/bin/python3", &command).await
}
async fn refresh(ui: &slint::Weak<AppWindow>) -> Result<()> {
    let state = system::service_state().await?;
    let active = state == "active";
    let message = match state.as_str() {
        "active" => "Servidor activo · Listo para Android",
        "activating" => "Preparando el audio…",
        "deactivating" => "Restaurando la salida de audio…",
        "failed" => "El servicio falló · Consulta journalctl --user -u redmi-audio",
        _ => "Audio detenido",
    };
    ui.upgrade_in_event_loop(move |ui| {
        ui.set_active(active);
        ui.set_service_status(message.into());
    })?;
    Ok(())
}
async fn pairing(state: &State, ui: &slint::Weak<AppWindow>) -> Result<()> {
    ui.upgrade_in_event_loop(|ui| {
        ui.set_qr_ready(false);
        ui.set_pairing_link(SharedString::default());
        ui.set_pairing_note("Preparando el QR…".into());
    })?;
    let result = async {
        let cfg = state.config()?;
        let (base, note) = if cfg.connection == "direct" {
            let peer: String = serde_json::from_str(&helper(&["address"]).await?)?;
            (
                format!(
                    "redmiaudio://p2p/{}?profile={}&peer={peer}",
                    state.token,
                    cfg.profile.name()
                ),
                "Abre ReExAudio en Android y escanea este QR. El PC se conectará automáticamente."
                    .to_string(),
            )
        } else {
            let sock = UdpSocket::bind("0.0.0.0:0")?;
            sock.connect("1.1.1.1:80")
                .context("No hay una ruta de red local disponible")?;
            let address = sock.local_addr()?.ip();
            (
                format!(
                    "http://{address}:53317/{}?profile={}",
                    state.token,
                    cfg.profile.name()
                ),
                format!("Red local: {address} · Inicia el audio para conectar."),
            )
        };
        let qr = qrcode::QrCode::new(base.as_bytes())?;
        let modules = qr.width() + 8;
        let scale = 256 / modules;
        let size = modules * scale;
        let mut pixels = vec![255u8; size * size * 3];
        for y in 0..qr.width() {
            for x in 0..qr.width() {
                if qr[(x, y)] == qrcode::Color::Dark {
                    for dy in 0..scale {
                        for dx in 0..scale {
                            let offset = (((y + 4) * scale + dy) * size + (x + 4) * scale + dx) * 3;
                            pixels[offset..offset + 3].fill(0);
                        }
                    }
                }
            }
        }
        ui.upgrade_in_event_loop(move |ui| {
            let buffer = slint::SharedPixelBuffer::<slint::Rgb8Pixel>::clone_from_slice(
                &pixels,
                size as u32,
                size as u32,
            );
            ui.set_qr(slint::Image::from_rgb8(buffer));
            ui.set_pairing_link(base.into());
            ui.set_pairing_note(note.into());
            ui.set_qr_ready(true);
        })?;
        Ok::<_, anyhow::Error>(())
    }
    .await;
    if let Err(ref e) = result {
        let message = format!("{e:#}");
        let _ = ui.upgrade_in_event_loop(move |ui| ui.set_pairing_note(message.into()));
    }
    result
}
