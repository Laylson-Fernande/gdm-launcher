# GDM K4811 App suite

> A custom home screen and companion apps for **NWD** Android car head units (1280×720), built with one goal: **an amazing in-car experience**.

🌐 **Website:** [garagemdomadeira.com](https://garagemdomadeira.com) · 📺 **YouTube:** [Garagem do Madeira](https://www.youtube.com/@garagemdomadeira)

---

## English

**GDM K4811 App suite** is an open-source suite for NWD head units: a light home screen plus separate apps for radio, music, Bluetooth, phone, equalizer, fan and settings. Same look, same dock, one audio source at a time.

The project is part of **Garagem do Madeira**, a space about cars, DIY and technology. Follow the work on [garagemdomadeira.com](https://garagemdomadeira.com) and [@garagemdomadeira](https://www.youtube.com/@garagemdomadeira).

**The focus is the experience:** big touch targets, readable at a glance, smooth animations, nothing that distracts you from the road, and nothing that changes your audio unless you ask for it.

## Português

O **GDM K4811 App suite** é uma suíte de código aberto para centrais NWD: uma tela inicial leve e apps separados para rádio, música, Bluetooth, telefone, equalizador, ventoinha e configurações. Mesmo visual, mesmo dock, uma fonte de áudio por vez.

O projeto faz parte da **Garagem do Madeira**, um espaço sobre carros, faça-você-mesmo e tecnologia. Acompanhe no site [garagemdomadeira.com](https://garagemdomadeira.com) e no canal [@garagemdomadeira](https://www.youtube.com/@garagemdomadeira).

**O foco é a experiência:** botões grandes, leitura rápida, animações suaves, nada que tire a atenção da estrada e nada que mexa no áudio sem você pedir.

## Español

**GDM K4811 App suite** es una suite de código abierto para radios NWD: una pantalla de inicio ligera y apps separadas para radio, música, Bluetooth, teléfono, ecualizador, ventilador y ajustes. Misma apariencia, mismo dock, una fuente de audio a la vez.

El proyecto forma parte de **Garagem do Madeira**. Sigue el trabajo en [garagemdomadeira.com](https://garagemdomadeira.com) y [@garagemdomadeira](https://www.youtube.com/@garagemdomadeira).

**El foco es la experiencia:** botones grandes, lectura rápida, animaciones suaves, nada que distraiga de la carretera y nada que cambie el audio sin que lo pidas.

---

## Apps

Cada pasta tem o seu próprio README com detalhes daquele app:

| App | Pasta | Package | README |
|---|---|---|---|
| Launcher (home + drawer) | `launcher/` | `com.garagemdomadeira.nwd.launcher` | [README](launcher/README.md) |
| Settings (engrenagem) | `car-setting/` | `com.nwd.car.setting` | [README](car-setting/README.md) |
| Radio | `radio/` | `com.nwd.radio` | [README](radio/README.md) |
| Music (MP3) | `music/` | `com.nwd.android.music.ui` | [README](music/README.md) |
| Bluetooth music | `bt-music/` | `com.nwd.bt.music` | [README](bt-music/README.md) |
| Phone | `phone/` | `com.nwd.android.phone` | [README](phone/README.md) |
| Equalizer | `eq/` | `com.nwd.audioset` | [README](eq/README.md) |
| Fan | `fan/` | `com.nwd.cpumonitor` | [README](fan/README.md) |
| Common (biblioteca) | `common/` | — | [README](common/README.md) |

Function apps that replace stock NWD apps use the **same package** as the originals. Launcher and car-setting install **alongside** the stock apps.

---

## Features

| Screen | What it does |
|---|---|
| **Home** | Modules on a 6-slot grid (clock, volume, now playing, fan, Bluetooth, image, GPS…), customizable dock, day/night and voltage on the top bar |
| **Radio** | FM/AM with RDS, station art, 12 favorites per band, tuning dial, Stereo / Distant |
| **Music** | Player with album art and track list from a chosen folder |
| **Bluetooth music** | A2DP/AVRCP controls and track info |
| **Phone** | Dialer, contacts, history and in-call UI |
| **Equalizer** | 10-band EQ, presets, loudness, fader/balance |
| **Fan** | Cooling fan preferences |
| **Apps** | Paged grid of installed apps |
| **Settings** | Language, theme, font, home layout, dock, assists, system options |

Opening home **never** turns the radio on or interrupts playback.

**One source at a time:** starting radio, MP3 or Bluetooth music pauses the other two — across apps.

**8 languages**, **14 theme colors**, **6 fonts** — set once in car-setting, followed by every app.

Shared config lives in `/sdcard/nwd-configs/launcher.json` (only car-setting writes it).

Radio art and `radio.config.json` live under `/sdcard/K4811/radio/` on internal storage.

---

## Build and install

Requires the Android SDK (`sdk.dir=...` in `local.properties`).

```bash
./gradlew assembleRelease            # all apps
./gradlew :radio:assembleRelease     # one app
```

APKs land in `<app>/build/outputs/apk/release/<app>-release.apk`.

Release signing uses `keystore.properties` at the repo root (see `keystore.properties.example`). Without it, release builds use the debug key.

```bash
./copy-to-updateApk.sh --build       # build and copy all APKs to updateApk/
./copy-apks.sh --build               # build and copy to the UpdateApk USB folder
```

Set the launcher as home (after install):

```bash
adb shell cmd package set-home-activity com.garagemdomadeira.nwd.launcher/.HomeActivity
```

**Without adb:** copy the APKs to a USB drive / `UpdateApk` card, install them on the unit, press Home and choose the Garagem launcher → **Always**.

### Stock launcher again

```bash
adb uninstall com.garagemdomadeira.nwd.launcher
```

**Emergency exit:** long-press the empty area of the home screen to open Android Settings.

---

## Developer mode

Create `/sdcard/LAUNCHER_DEV_MODE.txt` on the unit to show diagnostics, last error and the Advanced menu.

```bash
adb shell touch /sdcard/LAUNCHER_DEV_MODE.txt
```

### Radio demo (no head unit)

```bash
adb shell am start -n com.nwd.radio/com.k4811.radio.RadioActivity --ez demo true
```

---

## Technical notes

| | |
|---|---|
| Language | Pure Java, classic Views + XML, **no external libraries** |
| minSdk / targetSdk | 17 / 19 |
| Design | 1280×720 (portrait layouts where available for 768×1024) |
| Guide for contributors / agents | [`AGENTS.md`](AGENTS.md) · `SPEC.md` in each module |

Java packages live under `com.k4811.*`. Application IDs are defined only in the root `build.gradle.kts`.

---

## Project structure

```
common/          shared dock, theme, NWD clients
launcher/        home + app drawer
car-setting/     settings
radio/ music/ bt-music/ phone/ eq/ fan/
```

---

## Disclaimer

This is an independent, community project. It is **not affiliated with or endorsed by NWD** or any head unit manufacturer. Use it at your own risk: it talks to the head unit's system services, and factory options (developer mode) can change how your unit behaves.

---

<p align="center">Made with care at <a href="https://garagemdomadeira.com">Garagem do Madeira</a></p>
