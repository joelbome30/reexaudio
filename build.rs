fn main() {
    println!("cargo:rerun-if-changed=android/app/build.gradle");
    let gradle = std::fs::read_to_string("android/app/build.gradle").unwrap();
    let version = gradle
        .lines()
        .find_map(|line| {
            line.trim()
                .strip_prefix("versionName '")
                .and_then(|s| s.strip_suffix('\''))
        })
        .expect("Android versionName");
    println!("cargo:rustc-env=REEXAUDIO_VERSION={version}");
    #[cfg(feature = "desktop")]
    {
        let config = slint_build::CompilerConfiguration::new().with_library_paths(
            std::collections::HashMap::from([(
                "material".into(),
                std::path::PathBuf::from("vendor/material-1.1.0/material.slint"),
            )]),
        );
        slint_build::compile_with_config("ui/main.slint", config).unwrap();
    }
}
