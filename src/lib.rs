pub mod audio;
pub mod config;
#[cfg(feature = "desktop")]
pub mod desktop;
pub mod server;
pub mod system;
pub const VERSION: &str = env!("REEXAUDIO_VERSION");
pub const SERVICE: &str = "redmi-audio.service";
pub const VIRTUAL: &str = "redmi_phone";
