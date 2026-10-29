# Visão geral

## O que é

A **K4811 App suite** é um conjunto de apps Android que substituem os apps de fábrica de uma central multimídia automotiva **NWD** (tela 1280×720, Android 10 a 15, SoC chinês). Ela traz uma tela inicial leve e apps separados para rádio, música, Bluetooth, telefone, equalizador, ventoinha e configurações, todos com o mesmo visual, o mesmo dock e **uma fonte de áudio por vez**.

Os apps conversam com serviços proprietários da NWD (rádio, Bluetooth, SettingService, MCU) que **só existem na central**. Por isso quase nada funciona de verdade fora dela (ver [Ambiente local e testes](ambiente-local.md)).

## Os apps

Cada pasta da raiz é um módulo Gradle. Todos dependem do módulo `common`.

| Pasta | App | applicationId | Pacote Java | Na central |
|---|---|---|---|---|
| `common` | biblioteca compartilhada | — | `com.k4811.common` | entra em todos |
| `map` | biblioteca: mapa e navegação OpenStreetMap ([README](../map/README.md)) | — | `com.k4811.map` | entra no launcher |
| `launcher` | tela inicial, tela de navegação ([Home 2](tela-de-navegacao.md)) e gaveta de apps | `com.garagemdomadeira.nwd.launcher` | `com.k4811.launcher` | ao lado do launcher original |
| `car-setting` | configurações (engrenagem) | `com.nwd.car.setting` | `com.k4811.car.setting` | ao lado do original |
| `radio` | rádio FM/AM com RDS e favoritas | `com.nwd.radio` | `com.k4811.radio` | por cima do original |
| `music` | MP3 (pasta local, USB, SD) | `com.nwd.android.music.ui` | `com.k4811.android.music.ui` | por cima do original |
| `bt-music` | música Bluetooth (A2DP/AVRCP) | `com.nwd.bt.music` | `com.k4811.bt.music` | por cima do original |
| `phone` | telefone Bluetooth (HFP) | `com.nwd.android.phone` | `com.k4811.android.phone` | por cima do original |
| `eq` | equalizador de 10 bandas / DSP | `com.nwd.audioset` | `com.k4811.audioset` | por cima do original |
| `fan` | ventoinha | `com.nwd.cpumonitor` | `com.k4811.cpumonitor` | por cima do original |

- **"Por cima do original"**: o app usa o mesmo package do app de fábrica e só instala se for assinado com a **mesma chave** do original e tiver `versionCode` maior. Com outra chave a instalação falha com `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.
- **"Ao lado"**: package próprio, convive com o app de fábrica. O car-setting não pode substituir o original porque ele usa `sharedUserId com.android.settings` (exige a chave de plataforma).
- O `applicationId` de cada app é definido **só** na tabela `packages` do [`build.gradle.kts`](../build.gradle.kts) da raiz. O código usa `com.k4811.common.Apps` para saber os packages.

Detalhes de cada app: `<app>/README.md` e `<app>/SPEC.md`.

## Tecnologia

| | |
|---|---|
| Linguagem | Java 8 (lambdas ok), Views clássicas e layouts XML |
| Dependências | **nenhuma**: só o SDK do Android (sem AndroidX, Kotlin ou Compose) |
| minSdk / targetSdk | 17 / 19 (intencional, ver abaixo) |
| compileSdk | 36 (só define as APIs conhecidas pelo compilador) |
| Build | Gradle 8.14.3 (wrapper), Android Gradle Plugin 8.13.2, Kotlin DSL nos scripts |
| Release | R8 ligado, sem ofuscação, mantendo `com.nwd.**` e `com.bt.**` (AIDL dos serviços da NWD) |
| Idiomas | 8: en (padrão), pt, es, fr, ru, ja, zh, hi |

**Por que targetSdk 19:** com target < 23 as permissões perigosas (armazenamento, `SYSTEM_ALERT_WINDOW`, `WRITE_SETTINGS`, localização) são concedidas na instalação, e com target < 26/29 não há limite de serviço em segundo plano nem scoped storage. Também é o que permite os broadcasts implícitos entre os apps. **Não suba o targetSdk.**

## Arquitetura

### Módulo `common`

Tudo que mais de um app usa: dock e navegação entre apps, tema/fonte/idioma/densidade, configuração compartilhada, "uma fonte por vez", barra flutuante da NWD, clientes dos serviços da NWD (rádio, Bluetooth, SettingService), grade da tela inicial, strings nos 8 idiomas, drawables, estilos e fontes. Mapa completo em [`common/SPEC.md`](../common/SPEC.md).

### Configuração compartilhada

Como cada app tem um package diferente, SharedPreferences não é compartilhável. As configurações gerais (idioma, tema, modo escuro, fonte, itens do dock, layout da tela inicial etc.) ficam em:

```
/sdcard/nwd-configs/launcher.json
```

- **Só o car-setting grava**; os outros apps só leem.
- Gravação atômica (`.tmp` + `renameTo`) e leitura com cache por `lastModified()`.
- Preferências de um app só ficam no próprio app (ex.: EQ no SharedPreferences) ou em JSON editável em `/sdcard/K4811/<app>/` (rádio e MP3).

### Comunicação entre os apps

Por broadcasts da suíte (todos começam com `com.k4811.`):

| Action | Para quê |
|---|---|
| `com.k4811.action.PAUSE_PLAYERS` | rádio/BT/launcher pedem para o MP3 pausar |
| `com.k4811.action.MUSIC_STATE` | o MP3 avisa o launcher do que está tocando |
| `com.k4811.action.MUSIC_QUERY` / `MUSIC_COMMAND` | o launcher consulta e controla o MP3 |
| `com.k4811.launcher.action.APPS` | abrir a gaveta de apps |
| `com.k4811.launcher.action.NAV_HOME` | abrir a tela de navegação |
| `com.k4811.action.BOTTOM_BAR_QUERY` | coordenar a barra flutuante da NWD entre processos |

### Uma fonte por vez

Antes de rádio, MP3 ou música Bluetooth começar, as outras param (`common/.../audio/PlayerHandoff.java`). A fonte atual vem de `Settings.System mcu_current_source` (0 = Android, 4 = rádio, 7 = AUX, 8 = DVR). Há esperas propositais nesse fluxo: se a fonte sai do Android enquanto o MP3 ainda segura o foco de áudio, o sistema da NWD mata o app de música.

### Layout e densidade

Todo layout é desenhado em 1280×720 "dp". `Ui.applyDesignDensity(activity)` faz 1 dp = 1 px desse desenho e aplica idioma e tema. Launcher, car-setting e MP3 também têm layout vertical (768×1024).

## Estrutura do repositório

```
build.gradle.kts         tabela de packages, assinatura, versão de beta
settings.gradle.kts      lista dos módulos
gradle.properties        memória do Gradle, nonTransitiveRClass=false
keystore.properties.example   modelo da configuração de assinatura
common/                  biblioteca compartilhada
map/                     mapa e navegação (OpenStreetMap), usado pelo launcher
launcher/ car-setting/ radio/ music/ bt-music/ phone/ eq/ fan/   um app por pasta
copy-apks.sh             copia os APKs para o pen drive
copy-to-updateApk.sh     copia os APKs para updateApk/ na raiz
releases/                notas de versão (uma por versão)
tools/road-data/         gerador dos dados das vias (radares, limites), fora do build dos apps
.github/workflows/       release.yml (main), beta.yml (branch beta) e road-data.yml (dados das vias, toda semana)
docs/                    esta documentação
```

Cada app tem `src/main/java/com/k4811/...`, `src/main/res`, `AndroidManifest.xml`, `build.gradle.kts` (versão, minify) e `proguard-rules.pro`.
