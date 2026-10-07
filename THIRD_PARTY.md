# Third-party components

The desktop uses [Slint](https://slint.dev/), under its free desktop/mobile/web
[royalty-free license](https://github.com/slint-ui/slint/blob/v1.18.1/LICENSES/LicenseRef-Slint-Royalty-free-2.0.md).
The required AboutSlint widget is accessible from the top-level “Acerca de” button.
The license text is included in vendor/Slint-LICENSE.md.
This choice does not assign a new license to the ReExAudio source code.

`vendor/material-1.1.0/` is the Slint Material Components 1.1.0 release,
downloaded from https://material.slint.dev/zip/material-1.1.0.zip.
It is provided under the MIT license included in that directory.
One local display fix is documented in vendor/PATCHES.md. These sources
are vendored so building the UI does not download unpinned design assets.

Cargo dependencies and their versions are recorded in Cargo.lock. Their license
texts are included in the respective source packages downloaded by Cargo.
