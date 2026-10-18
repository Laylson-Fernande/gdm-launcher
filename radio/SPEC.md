# SPEC · radio

Leia antes o [`AGENTS.md`](../AGENTS.md) e o [`common/SPEC.md`](../common/SPEC.md).

| | |
|---|---|
| applicationId | `com.nwd.radio` (por cima do original) |
| Pacote Java | `com.k4811.radio` |
| Application | `RadioApp` (SuiteApp + `PlayerHandoff.init`) |
| Activity | `RadioActivity` (singleTask, `nosensor`) + alias `com.nwd.radio.home_horizontalActivity` |
| Protocolo | `radio apk/com.nwd.radio/COMANDOS.md` |

## Responsabilidade

Interface do sintonizador físico. Quem sintoniza é o serviço de rádio da NWD (`common/radio/RadioClient`, AIDL `RadioFeature`/`RadioCallback`).

## Tela

Memórias (12 por banda, `ui/PresetCardView`, numa linha acima do dock), dial (`ui/TunerDialView`) no alto, ⏮ ‹ › ⏭ ao lado da estação, Rádio (liga/desliga), Stereo, Distante, Banda (cicla FM1 → AM2), teclado numérico (`dialog_keypad`), diagnóstico no modo desenvolvedor (`dialog_diag`, segurar a frequência). Um só `activity_main.xml`, também na vertical.

## Fonte de áudio

- `onResume`: se o rádio está ligado e não é a fonte, `PlayerHandoff.switchToRadio(this, radio::activateSource)` (pausa MP3 e BT; espera 0,6 s se a fonte era Android).
- Power desliga: `deactivateSource` (`ACTION_REQUEST_CHANGE_SOURCE` com fonte 0).
- `activateSource` envia `ACTION_APP_IN_OUT` com app 8.

## Arquivos (`K4811/radio/` na memória interna; procura em `ro.nwd.internal.path` e em `/sdcard`)

- `radio.config.json` (`PresetStore`): `last` (banda e frequência) e `bands` → `M1..M12` (FM em MHz, AM em kHz). Arquivo com erro: avisa e não grava. Sem arquivo: importa de `persist.k4811.*`.
- Arte `FM_<freq>.jpg` / `AM_<freq>.png` (`StationArt`).

## Como verificar

`./gradlew :radio:assembleRelease`; modo demo: `adb shell am start -n com.nwd.radio/com.k4811.radio.RadioActivity --ez demo true`.
