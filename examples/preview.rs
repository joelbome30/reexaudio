//! Visual smoke check without starting a service, touching audio, or using real pairing data.
use reexaudio::desktop::{AppWindow, MenuItem};
use slint::platform::{
    Platform, WindowAdapter,
    software_renderer::{MinimalSoftwareWindow, RepaintBufferType},
};
use slint::{ComponentHandle, ModelRc, VecModel};
use std::{io::Write, rc::Rc};
struct PreviewPlatform(Rc<MinimalSoftwareWindow>);
impl Platform for PreviewPlatform {
    fn create_window_adapter(&self) -> Result<Rc<dyn WindowAdapter>, slint::PlatformError> {
        Ok(self.0.clone())
    }
}

fn main() -> anyhow::Result<()> {
    let output = std::env::args()
        .nth(1)
        .unwrap_or_else(|| "/tmp/reexaudio-preview".into());
    let window = MinimalSoftwareWindow::new(RepaintBufferType::NewBuffer);
    slint::platform::set_platform(Box::new(PreviewPlatform(window.clone())))?;
    let ui = AppWindow::new()?;
    ui.set_busy(false);
    ui.set_service_status("Audio detenido".into());
    ui.set_feedback("Vista de prueba · Sin acceso al audio ni al servicio".into());
    ui.set_version(reexaudio::VERSION.into());
    ui.set_outputs(ModelRc::new(VecModel::from(vec![MenuItem {
        text: "Altavoces del PC".into(),
        enabled: true,
        ..Default::default()
    }])));
    ui.set_output_index(0);
    ui.set_peers(ModelRc::new(VecModel::from(vec![MenuItem {
        text: "Android · AA:BB:CC:DD:EE:FF".into(),
        enabled: true,
        ..Default::default()
    }])));
    ui.set_peer_index(0);
    ui.set_pairing_note(
        "Escanea el QR en Android. Después busca el celular y acepta la conexión.".into(),
    );
    let qr = qrcode::QrCode::new(
        b"redmiaudio://p2p/preview-only?profile=balanced&peer=AA:BB:CC:DD:EE:FF",
    )?;
    let width = (qr.width() + 8) as u32;
    let mut image = slint::SharedPixelBuffer::<slint::Rgb8Pixel>::new(width, width);
    for y in 0..width {
        for x in 0..width {
            let dark = x >= 4
                && y >= 4
                && x < width - 4
                && y < width - 4
                && qr[((x - 4) as usize, (y - 4) as usize)] == qrcode::Color::Dark;
            let v = if dark { 0 } else { 255 };
            image.make_mut_slice()[(y * width + x) as usize] = slint::Rgb8Pixel::new(v, v, v);
        }
    }
    ui.set_qr(slint::Image::from_rgb8(image));
    ui.set_qr_ready(true);
    ui.show()?;
    for (step, dark, width, height) in [
        (0, true, 920, 800),
        (1, false, 920, 800),
        (2, false, 500, 580),
        (3, false, 500, 580),
    ] {
        ui.set_dark(dark);
        window.set_size(slint::PhysicalSize::new(width, height));
        slint::platform::update_timers_and_animations();
        if step == 3 {
            window.dispatch_event(slint::platform::WindowEvent::PointerScrolled {
                position: slint::LogicalPosition::new(250., 320.),
                delta_x: 0.,
                delta_y: -560.,
            });
        }
        let pixels = ui.window().take_snapshot()?;
        let mut file = std::fs::File::create(format!("{output}-{step}.ppm"))?;
        write!(file, "P6\n{} {}\n255\n", pixels.width(), pixels.height())?;
        let rgb: Vec<u8> = pixels
            .as_slice()
            .iter()
            .flat_map(|p| [p.r, p.g, p.b])
            .collect();
        file.write_all(&rgb)?;
    }
    ui.hide()?;
    Ok(())
}
