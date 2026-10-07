use anyhow::{Context, Result, bail};
use serde::{Deserialize, Serialize};
use std::{
    fs::{self, OpenOptions},
    io::{Read, Write},
    os::unix::fs::{OpenOptionsExt, PermissionsExt},
    path::{Path, PathBuf},
};

#[derive(Clone, Copy, Debug, Default, Deserialize, Serialize, PartialEq)]
#[serde(rename_all = "lowercase")]
pub enum Profile {
    Performance,
    #[default]
    Balanced,
    Quality,
}
impl Profile {
    pub fn name(self) -> &'static str {
        match self {
            Self::Performance => "performance",
            Self::Balanced => "balanced",
            Self::Quality => "quality",
        }
    }
    pub fn audio(self) -> (u32, u32, usize) {
        match self {
            Self::Performance => (10, 15, 1920),
            Self::Balanced => (20, 40, 3840),
            Self::Quality => (40, 80, 7680),
        }
    }
    pub fn from_name(s: &str) -> Self {
        match s {
            "performance" => Self::Performance,
            "quality" => Self::Quality,
            _ => Self::Balanced,
        }
    }
}
#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(default)]
pub struct Config {
    pub reverse_sink: String,
    pub pc_audio: bool,
    pub volume: u16,
    pub connection: String,
    pub profile: Profile,
    pub dark: bool,
    pub accent: u8,
    #[serde(flatten)]
    pub extra: std::collections::BTreeMap<String, serde_json::Value>,
}
impl Default for Config {
    fn default() -> Self {
        Self {
            reverse_sink: String::new(),
            pc_audio: true,
            volume: 100,
            connection: "direct".into(),
            profile: Profile::Balanced,
            dark: true,
            accent: 0,
            extra: Default::default(),
        }
    }
}
#[derive(Clone)]
pub struct State {
    pub dir: PathBuf,
    pub token: String,
}
impl State {
    pub fn load() -> Result<Self> {
        let dir = if let Some(p) = std::env::var_os("REEXAUDIO_STATE_DIR") {
            PathBuf::from(p)
        } else {
            PathBuf::from(std::env::var_os("HOME").context("HOME no está definido")?)
                .join(".local/state/redmi-audio")
        };
        Self::at(dir)
    }
    pub fn at(dir: PathBuf) -> Result<Self> {
        fs::create_dir_all(&dir)?;
        fs::set_permissions(&dir, fs::Permissions::from_mode(0o700))?;
        let path = dir.join("token");
        if !path.exists() {
            let mut bytes = [0u8; 24];
            fs::File::open("/dev/urandom")?.read_exact(&mut bytes)?;
            let token: String = bytes.iter().map(|b| format!("{b:02x}")).collect();
            match OpenOptions::new()
                .write(true)
                .create_new(true)
                .mode(0o600)
                .open(&path)
            {
                Ok(mut file) => {
                    file.write_all(token.as_bytes())?;
                    file.sync_all()?;
                }
                Err(e) if e.kind() == std::io::ErrorKind::AlreadyExists => {}
                Err(e) => return Err(e.into()),
            }
        }
        let token = fs::read_to_string(path)?.trim().to_owned();
        if token.is_empty()
            || !token
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
        {
            bail!("Token de emparejamiento inválido");
        }
        Ok(Self { dir, token })
    }
    pub fn config(&self) -> Result<Config> {
        let path = self.dir.join("config.json");
        let mut cfg: Config = match fs::read(&path) {
            Ok(data) => serde_json::from_slice(&data)
                .context("config.json no es válido; conserva una copia antes de corregirlo")?,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Config::default(),
            Err(e) => return Err(e.into()),
        };
        cfg.volume = cfg.volume.min(150);
        cfg.accent = cfg.accent.min(2);
        if cfg.connection != "local" {
            cfg.connection = "direct".into();
        }
        Ok(cfg)
    }
    pub fn save(&self, cfg: &Config) -> Result<()> {
        atomic_json(&self.dir.join("config.json"), cfg)
    }
}
pub fn atomic_json(path: &Path, value: &impl Serialize) -> Result<()> {
    let temp = path.with_extension(format!("{}.tmp", std::process::id()));
    let result = (|| -> Result<()> {
        let mut file = OpenOptions::new()
            .write(true)
            .create(true)
            .truncate(true)
            .mode(0o600)
            .open(&temp)?;
        file.write_all(&serde_json::to_vec_pretty(value)?)?;
        file.sync_all()?;
        fs::rename(&temp, path)?;
        Ok(())
    })();
    if result.is_err() {
        let _ = fs::remove_file(temp);
    }
    result
}
pub fn asset(name: &str) -> PathBuf {
    let installed = std::env::current_exe()
        .unwrap_or_default()
        .with_file_name(name);
    if installed.exists() {
        installed
    } else if Path::new("/usr/lib/reexaudio").join(name).exists() {
        Path::new("/usr/lib/reexaudio").join(name)
    } else {
        Path::new(env!("CARGO_MANIFEST_DIR")).join(name)
    }
}
