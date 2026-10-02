<p align="center">
  <img src="docs/images/icon.png" alt="Android Code Studio" width="72" height="72" />
</p>

<h2 align="center">Android Code Studio</h2>

<p align="center">
  An IDE to develop real, Gradle-based Android applications on Android devices.
</p>

<p align="center">
  <!-- Latest release -->
  <img src="https://img.shields.io/github/v/release/HUIYWU/huiywu.androidcodestudio?include_prereleases&amp;label=latest%20release" alt="Latest release" />
</p>

<p align="center">
  <!-- License -->
  <img src="https://img.shields.io/badge/License-GPLv3-blue.svg" alt="License" />
</p>

<p align="center">
  <a href="https://docs.androidide.com/">
    <img src="https://img.shields.io/badge/Docs-Explore-blue?style=for-the-badge&logo=read-the-docs" alt="Explore the docs" />
  </a>
</p>

## Features

- [x] Gradle support
- [x] JDK 11 & JDK 17 available
- [x] Terminal with essential packages
- [x] API information for classes and their members (since, removed, deprecated)
- [x] Log reader (real-time logs for your app)
- [ ] Language servers
    - [x] Java
    - [x] XML
    - [x] Kotlin
- [ ] UI Designer
    - [x] Layout inflater
    - [x] Resolve resource references
    - [x] Auto-complete resource values when editing attributes
    - [x] Drag & Drop interface
    - [x] Visual attribute editor
    - [x] Android Widgets
- [ ] String Translator
- [x] Asset Studio (Drawable & Icon Maker)
- [x] Plugin Creator (Create sub-modules or plugins inside your project)
- [x] Git integration
- [x] **AI Agent** – fully **project-aware AI assistant** that understands your code, modules, and project structure to help with coding.
<!-- - debugging, and suggestions-->

## Limitations

- For working with projects in AndroidCodeStudio, your project must use Android Gradle Plugin v7.2.0 or
  newer. Projects with older AGP must be migrated to newer versions.
- SDK Manager is already included in Android SDK and is accessible in Android Code Studio via its Terminal.
  But, you cannot use it to install some tools (like NDK) because those tools are not built for
  Android.

The app is still being developed actively. It's in beta stage and may not be stable. if you have any
issues using the app, please let us know.

## Thanks to

- [Rosemoe](https://github.com/Rosemoe) for the
  awesome [CodeEditor](https://github.com/Rosemoe/sora-editor)
- [Termux](https://github.com/termux) for [Terminal Emulator](https://github.com/termux/termux-app)
- [Bogdan Melnychuk](https://github.com/bmelnychuk)
  for [AndroidTreeView](https://github.com/bmelnychuk/AndroidTreeView)
- [George Fraser](https://github.com/georgewfraser) for
  the [Java Language Server](https://github.com/georgewfraser/java-language-server)
- [FWCD](https://github.com/fwcd) for
  the [Kotlin Language Server](https://github.com/fwcd/kotlin-language-server)
- [Itsaky](https://github.com/itsaky) for [AndroidIDE](https://github.com/AndroidIDEOfficial/AndroidIDE.git).
- [Mohammed-Baqer-null](https://github.com/Mohammed-Baqer-null) for [AndroidCS](https://github.com/AndroidCSOfficial/android-code-studio.git)
  
Thanks to all the developers who have contributed to this project.

## License

```
AndroidCodeStudio is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

AndroidCodeStudio is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
```

Any violations to the license can be reported either by opening an issue or writing a mail to us
directly.
