# AGENTS.md — guia para agentes de IA

Leia este arquivo inteiro antes de mexer no projeto. Depois leia o `SPEC.md` do módulo em que vai trabalhar. Os `README.md` são para pessoas; os `SPEC.md` são a especificação técnica de cada app.

## O que é

**GDM K4811 App suite**: um conjunto de apps Android que substituem os apps de fábrica de uma central multimídia automotiva **NWD** (tela 1280×720, Android 10 a 15, SoC chinês; a central de testes atual é uma K2401 com Android 15, firmware `K2401_NWD_S212802`). Cada pasta da raiz é um app. Todos compartilham o módulo `common` (dock, tema, fonte, idioma, clientes dos serviços da NWD).

A engenharia reversa dos apps originais (APKs decompilados, protocolos) fica fora deste repositório. Os SPECs podem citar pastas no formato `radio apk/com.nwd.radio/…` como referência a esse material. Consulte-o antes de inventar um protocolo da NWD.

## Regras que não mudam

1. **Java puro, Views clássicas e layouts XML. Nenhuma biblioteca**, nem AndroidX, nem Kotlin, nem Compose. Só o SDK do Android e o `org.json` que vem nele.
2. **minSdk 17 / targetSdk 19** em todos os apps. É intencional: com target < 23 as permissões perigosas (armazenamento, `SYSTEM_ALERT_WINDOW`, `WRITE_SETTINGS`, localização) são concedidas na instalação, e com target < 26/29 não há limite de serviço em segundo plano nem scoped storage. **Não suba o targetSdk.**
3. Java 8 (lambdas ok). compileSdk 36 só define as APIs conhecidas.
4. Código Java sempre em `com.k4811.*` (exceções: os AIDL e o `Bundleable` do modo carro em `androidx.car.app`, no launcher, porque o Waze reconhece as interfaces pelo nome, como os `com.nwd.*`). O `applicationId` de cada app vem da tabela `packages` no `build.gradle.kts` da raiz. **Nunca escreva um package de app no código**: use `com.k4811.common.Apps` (que lê o `BuildConfig` do common).
5. **Strings visíveis ficam no `common/src/main/res/values*/strings_*.xml`**, nos 8 idiomas (en padrão, pt, es, fr, ru, ja, zh, hi), com prefixo da área (`home_`, `radio_`, `media_`, `settings_`, `adv_`, `eq_`, `audio_`). Textos só da suíte vão em `strings_suite.xml`. Ao criar uma string, crie nos 8 idiomas.
6. O projeto não tem nenhuma menção a "madeira", "garagem" ou "gdm" no código, nos recursos ou na documentação. A **única** exceção é o package do launcher, `com.garagemdomadeira.nwd.launcher`, pedido pelo dono. Não reintroduza esses nomes.
7. Comentários: o código portado tem comentários em inglês, o código da suíte em português. Siga o idioma do arquivo que estiver editando. A documentação (READMEs, SPECs, mensagens de commit) é em português.
8. Não suba APK, chave ou senha. O `.gitignore` já cobre `*.apk`, `*.jks`, `keystore.properties`, `local.properties`.

## Mapa dos módulos

| Pasta | App | Package (applicationId) | Pacote Java | Na central |
|---|---|---|---|---|
| `common` | biblioteca | — | `com.k4811.common` | entra em todos |
| `map` | biblioteca: mapa e navegação (OpenStreetMap, feito do zero) | — | `com.k4811.map` | entra no launcher |
| `launcher` | tela inicial e gaveta | `com.garagemdomadeira.nwd.launcher` | `com.k4811.launcher` | ao lado do `com.android.launcher` original |
| `car-setting` | configurações (engrenagem) | `com.nwd.car.setting` | `com.k4811.car.setting` | ao lado do `com.android.car.setting` original |
| `radio` | rádio FM/AM | `com.nwd.radio` | `com.k4811.radio` | por cima do original |
| `music` | MP3 (pasta local, USB, SD) | `com.nwd.android.music.ui` | `com.k4811.android.music.ui` | por cima do original |
| `bt-music` | música Bluetooth (A2DP/AVRCP) | `com.nwd.bt.music` | `com.k4811.bt.music` | por cima do original |
| `phone` | telefone Bluetooth (HFP) | `com.nwd.android.phone` | `com.k4811.android.phone` | por cima do original |
| `eq` | equalizador / DSP | `com.nwd.audioset` | `com.k4811.audioset` | por cima do original |
| `fan` | ventoinha | `com.nwd.cpumonitor` | `com.k4811.cpumonitor` | por cima do original |

Build pessoal (sem a chave): `k4811.packages=personal` no `local.properties` dá packages próprios (`com.k4811.radio`…) aos 6 apps "por cima do original", que ficam ao lado dos de fábrica. Ver `docs/gerar-apk.md`.

"Por cima do original" só funciona se o APK for assinado com a **mesma chave** do original e tiver versionCode maior (20270000). Com outra chave, a instalação falha com `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. O car-setting não substitui o original porque ele usa `sharedUserId com.android.settings`, que exige a chave de plataforma.

## Build

```bash
./gradlew assembleRelease                 # os 8 apps
./gradlew :radio:assembleRelease          # um app
./gradlew :radio:compileReleaseJavaWithJavac   # só compilar (rápido, para checar erros)
./copy-apks.sh --build                    # gera e copia para /Volumes/madera/UpdateApk (pen drive)
```

- Saída: `<app>/build/outputs/apk/release/<app>-release.apk`.
- Assinatura: `keystore.properties` na raiz (fora do git; modelo em `keystore.properties.example`). Sem ele, o release sai com a chave de debug.
- `android.nonTransitiveRClass=false`: cada app enxerga os recursos do common pelo próprio `R`.
- R8 ligado no release, com `-dontobfuscate` e `-keep class com.nwd.** / com.bt.**` (AIDL e Parcelables dos serviços da NWD não podem ser renomeados). O launcher também mantém `com.acloud.newinterface.MusicBean`.

**Não há testes automatizados.** "Pronto" significa: `./gradlew assembleRelease` sem erro e, quando mexer em manifest ou package, conferir o APK:

```bash
BT=$(ls -d ~/Library/Android/sdk/build-tools/*/ | tail -1)
${BT}aapt dump badging radio/build/outputs/apk/release/radio-release.apk | head -3
${BT}aapt dump xmltree radio/build/outputs/apk/release/radio-release.apk AndroidManifest.xml
${BT}apksigner verify --print-certs radio/build/outputs/apk/release/radio-release.apk
```

Nada roda no emulador de forma útil: os serviços da NWD (rádio, BT, SettingService, MCU) só existem na central. O rádio tem um modo demo: `adb shell am start -n com.nwd.radio/com.k4811.radio.RadioActivity --ez demo true`. Diga sempre que o comportamento não foi testado na central.

## Configuração compartilhada: `/sdcard/nwd-configs/launcher.json`

Cada app tem seu package, então SharedPreferences não é compartilhável. As configurações que todos seguem ficam num JSON em disco:

- **Só o car-setting grava** (`LauncherConfig.set…`). O launcher e os apps só leem. Não chame `LauncherConfig.set…` fora do car-setting.
- Gravação atômica (`NwdConfigs.write`: `.tmp` + `renameTo`). Leitura com cache por `lastModified()`.
- Chaves (versão 2): `language`, `theme`, `dark_mode`, `font`, `dock_toggles`, `zlink_name`, `zlink_auto_connect`, `zlink_auto_open`, `hide_bottom_bar`, `home_layout`, `home_image`, `show_voltmeter`, `show_day_night`, `screen_off_after`, `ask_default_launcher`, `nav_app`, `music_app`, `nav_info`, `nav_mode`, `nav_panel_right`, `nav_voice`, `nav_cameras`, `nav_speed_limit`, `nav_road_alerts`, `nav_data_days`, `nav_data_km`, `nav_auto_zoom`, `nav_perspective`, `nav_offline_map`, `nav_media_percent`, `notify_percent`, `show_sys_monitor`, `save_logs`, `dock_size`, `day_night_by`, `day_night_affects`, `night_start`, `day_start`, `start_guard`, `block_notifications`. Detalhes em `common/SPEC.md`.
- Preferência que só um app usa fica no SharedPreferences dele (ex.: o EQ em `EqStore`). Rádio e MP3 guardam as delas em JSON editável em `K4811/<app>/` (`PresetStore`, `MusicConfig`).

## Contratos entre os apps (broadcasts da suíte)

| Action | Quem envia | Quem recebe | Extras |
|---|---|---|---|
| `com.k4811.action.PAUSE_PLAYERS` | `PlayerHandoff` (rádio, BT, launcher) | music `PauseReceiver` | `from` = package de quem pediu |
| `com.k4811.action.MUSIC_STATE` | music `MusicPlaybackService` | launcher `HomeSession` | `has_track`, `title`, `artist`, `album`, `playing`, `position_ms`, `duration_ms`, `media_id`, `path` |
| `com.k4811.action.MUSIC_QUERY` | launcher | music `RemoteReceiver` | — (responde com `MUSIC_STATE` se o player estiver rodando) |
| `com.k4811.action.MUSIC_COMMAND` | launcher, phone `CallFloatService` | music `RemoteReceiver` | `cmd`: 0 tocar/pausar, 1 próxima, 2 anterior, 3 retomar se uma chamada pausou (telefone, ao silenciar) |
| `com.k4811.launcher.action.APPS` | `DockNavigator` (item Apps) | launcher `AppsActivity` | — |
| `com.k4811.launcher.action.NAV_HOME` | `DockNavigator` (item Navegação), car-setting | launcher `NavHomeActivity` | — |
| `com.k4811.action.SET_DARK_MODE` | launcher `status/BrightnessPopup` (interruptor da barra de brilho) e `status/DayNightWatcher` (troca de período, tema seguindo o dia/noite) | car-setting `DarkModeReceiver` (grava `dark_mode`; vale o último pedido) | `on` (boolean) |
| `com.k4811.action.SCREEN_SHOWN` | `SuiteApp` de cada app (tela que veio para a frente) | launcher `start/StartReceiver` (guarda a última tela da suíte) | `component` (ComponentName achatado) |
| `com.k4811.action.NAV_STATE` | tela própria do Waze modificado (projeto recompile-optimized, `RouteBroadcast`) | launcher `NavHomeActivity` (cartão da rota no painel, enquanto aberta) e `home/HomeRoute` (rota acima da hora na tela inicial, enquanto ela está na frente) | `app`, `active`; navegando: `distance`, `cue`, `icon` (Bitmap branco), `remaining`, `remaining_distance`, `arrival` (textos já formatados) |
| `com.k4811.action.NAV_QUERY` | launcher `NavHomeActivity` (`onStart`) e `home/HomeRoute` (`onResume` da tela inicial), com `setPackage` do app de navegação | Waze modificado (receiver dinâmico) | — (responde com o último `NAV_STATE`) |
| `com.k4811.action.NAV_PANEL` | launcher `NavHomeActivity` (`onStart`/`onStop` e em resposta a `NAV_PANEL_QUERY`, com `setPackage` do app de navegação) | Waze modificado (com o painel visível e em tela dividida, esconde os próprios cartões de rota) | `shown` (boolean), `instance` (elapsedRealtime da criação do painel: o app ignora "saí" de instância mais antiga, porque ao dividir a tela o painel fecha e reabre) |
| `com.k4811.action.NAV_PANEL_QUERY` | Waze modificado (ao aparecer ou entrar na tela dividida) | launcher `NavHomeActivity` (receiver dinâmico) | — (responde com `NAV_PANEL`) |
| `com.k4811.action.NAV_COMMAND` (explícito, `com.waze/com.k4811.wazeui.CommandReceiver`) | launcher `waze/WazeScreen` (✕ do Waze embutido) | Waze modificado | `cmd`: `stop` (encerra a navegação; o modo carro do Waze não tem essa ação) |
| `com.k4811.action.BOTTOM_BAR_QUERY` (ordenado) | `BottomBar` do app que parou | `BottomBar$Query` de todos | resultCode 1 = "tenho tela na frente" |
| `com.k4811.action.MEMORY_QUERY` (ordenado) | car-setting `system/MemoryPanel` (aba Memória) | `system/AppMemory` de cada app da suíte que está rodando (receiver dinâmico do `SuiteApp`) | resultado: extras com package → PSS em KB |
| `com.k4811.action.QUIT` | car-setting `system/MemoryPanel` (botão Encerrar, com `setPackage`) | `system/AppMemory` do app (para os próprios serviços e encerra o processo) | — |
| `com.k4811.action.NAV_DATA_CHECK` | car-setting `NavSettingsActivity` (mudou raio, período ou mapa offline; `setPackage` + `FLAG_INCLUDE_STOPPED_PACKAGES`) e o alarme de hora em hora do launcher | launcher `map/.../road/RoadSyncReceiver` (`RoadSync.start`: confere os dados baixados do mapa se venceu o prazo, mudou o raio ou falta a carga inicial) | — |

Ao abrir o app de navegação, a tela de navegação manda o extra `com.k4811.extra.DARK_MODE` (modo escuro em uso na suíte); o Waze modificado segue, os outros apps ignoram.

Com targetSdk 19, broadcast implícito ainda chega em receiver declarado no manifest, mesmo no Android 10. Se um dia o target subir, todos esses contratos quebram.

## Sistema da NWD (o que os apps usam)

- **Fonte de áudio atual:** `Settings.System mcu_current_source` (0 = Android, 4 = rádio, 7 = AUX, 8 = DVR) e o broadcast `com.nwd.action.ACTION_CHANGE_SOURCE`.
- **Pegar a fonte:** `com.nwd.action.ACTION_REQUEST_CHANGE_SOURCE` (`extra_source_id`, byte) e `com.nwd.action.ACTION_APP_IN_OUT` (`extra_app_id`: 2 música, 8 rádio, 10 telefone, 15 música BT; `extra_app_operation` 1, `extra_app_event` 0). Constantes em `NwdConstants`.
- **Serviços:** rádio (`com.nwd.radio.service.ACTION_RADIO_SERVICE`, AIDL `RadioFeature`), Bluetooth (`BtClient`, AIDL `com.bt.BTFeature`, broadcasts `com.bt.ACTION_*`), SettingService (`com.nwd.setting.service/.SettingService`, AIDL `SettingFeature`: volume, EQ pela MCU, brilho, bits do sistema). Os `.aidl` precisam manter a **ordem exata dos métodos** do original.
- **Props:** um app comum não faz `setprop persist.*`. Use `SystemProps.requestSet` (grava `Settings.System nwd_system_prop` e envia `com.nwd.action.ACTION_SET_SYSTEM_PROP`). Confira o resultado depois com `SystemProps.get`.
- **Barra flutuante da NWD:** `com.nwd.statusbarbottom.removeFloat` / `createFloat` com `pagename=home_horizontal`.

## Armadilhas conhecidas

- **Uma fonte por vez:** antes de uma fonte começar, as outras param (`PlayerHandoff.beforeRadio/beforeMp3/beforeBt/switchToRadio`). Se a fonte sai do Android enquanto o player do MP3 ainda segura o foco de áudio, **o sistema da NWD mata o app de música**. Por isso `switchToRadio` espera 0,6 s quando a fonte era Android e o `PauseReceiver` pausa de forma síncrona. Não remova essas esperas.
- **Densidade:** todo layout é desenhado em 1280×720 "dp". `Ui.applyDesignDensity(activity)` (antes do `setContentView`) faz 1 dp = 1 px desse desenho e também aplica idioma e tema. Toda Activity nova precisa chamar isso. Telas sem Activity (janela flutuante) calculam a própria escala (ver `phone/CallFloatService`).
- **Tema:** as cores vêm de `Ui.*` (não `final`) e o `ThemeInflater` troca cores literais do tema padrão nos layouts. Use `@color/...`, `@drawable/...` e `Ui.TEXT`, `Ui.SURFACE` etc., nunca uma cor nova solta, para o tema funcionar.
- **Idioma/tema/fonte mudam em outro app:** o launcher compara no `onResume` e chama `recreate()`. Os outros apps aplicam ao abrir (trocar de app pelo dock faz `finish()`).
- **Dock:** sempre por `DockNavigator.attachDock(activity, item)` logo depois do `setContentView`. Navegação entre apps por `DockNavigator.navigate`. No launcher abre na hora; dentro dos apps há um Splash de 1 s.
- **Barra da NWD entre processos:** o `BottomBar` de cada app só mostra a barra de volta depois de perguntar aos outros (ver contrato acima). Todo app precisa usar `com.k4811.common.SuiteApp` (ou uma subclasse) como `Application`.
- **Tela dividida (tela de navegação):** com targetSdk < 24 as Activities são "não redimensionáveis" e desfazem a tela dividida ao vir para a frente; a `NavHomeActivity` declara `resizeableActivity="true"`. A HOME do sistema nunca fica dividida, e uma tarefa que já existe não muda de modo ao vir para a frente: por isso a tela de navegação recria a própria tarefa na metade primária (`launcher/.../nav/SplitScreen.java`). Ver `launcher/SPEC.md`.
- **Telas verticais (768×1024):** launcher, car-setting e MP3 têm `layout-port` e `screenOrientation="nosensor"`; o rádio tem um layout só, em coluna, que serve nas duas. Os outros são só horizontais (`sensorLandscape`).
- **Android 14+ e targetSdk 19:** o Android 14/15 puro recusa instalar app com targetSdk < 23/24 (`INSTALL_FAILED_DEPRECATED_SDK_VERSION`). A ROM da NWD libera; no emulador use `adb install --bypass-low-target-sdk-block`. O aviso "app criado para uma versão mais antiga do Android" aparece no emulador, não na central.
- **Idioma por tela:** toda Activity sobrescreve `attachBaseContext` com `Ui.withLanguage(base)`. Só o `Ui.applyLanguage` (que usa `updateConfiguration`) se perde no Android 11+ quando a janela muda de tamanho (sair da tela dividida, por exemplo) sem `onConfigurationChanged`: os textos voltam ao idioma do sistema. Activity nova: copie o `attachBaseContext`.
- **Tela dividida no Android 12+:** a API oculta `setLaunchWindowingMode` é bloqueada e o modo "metade primária" não existe; o sistema divide quando a tela, em tela cheia, abre o app com `FLAG_ACTIVITY_LAUNCH_ADJACENT` (`SplitScreen.adjacentSplits()`). Ver `launcher/SPEC.md`.
- **Permissão nova numa atualização:** o launcher passou a usar internet (mapa integrado). No Android 10, uma permissão de instalação acrescentada numa *atualização* pode só valer depois de reiniciar a central (o socket falha com `EPERM`). Instalação nova não tem o problema.
- **Desempenho (central de 2 GB, 4 × 1,5 GHz):** nada de tique por segundo sem necessidade (o relógio da tela inicial vira o minuto), `setText` só quando o texto muda, animação só enquanto algo se mexe e a ~30 qps, nada desenhando com a janela escondida. Medições e método em `docs/desempenho.md`.
- **Dados das vias (radares, limites):** o endereço vem do build (`BuildConfig.ROAD_DATA_URL`, montado no `build.gradle.kts` da raiz a partir do repositório do GitHub, para não escrever o nome do repositório no código). Os quadrados não têm data dentro: o mesmo dado gera o mesmo arquivo e o app só baixa o que mudou. Mudou o formato? Suba a versão nos dois lados (`RoadDataBuilder` e `RoadCell`).
- **Log em arquivo:** com `save_logs` ligado (Configurações → Sistema), cada app grava o próprio logcat em `/sdcard/K4811/logs/<app>.log` (`common/.../system/LogFile.java`, até 2 MB + 1 anterior; o crash também vai direto para o arquivo). É o jeito de diagnosticar na central sem adb: peça esses arquivos.
- **Dia e noite:** o modo escuro é sempre o `dark_mode` gravado, e vale a última escolha. Com o tema seguindo o dia/noite, o `DayNightWatcher` do launcher só age na **troca** de período: pede `com.k4811.action.SET_DARK_MODE` ao car-setting. Entre as trocas, o motorista muda à vontade (Configurações, barra de brilho). Telas que ficam abertas comparam `Ui.lookKey` e se recriam. O brilho por horário é aplicado pelo `launcher/.../status/DayNightWatcher` (processo do launcher); com o farol, quem escurece é a MCU ("Escurecer com o farol", que o car-setting liga/desliga conforme a opção).
- **App parado à força não recebe broadcast:** a NWD (`NwdProcessAdjuster`) dá force-stop em apps, e um app nesse estado só recebe broadcast (mesmo com `setPackage`) se a intent tiver `Intent.FLAG_INCLUDE_STOPPED_PACKAGES`. Pedido a outro app da suíte que precisa chegar (ex.: `SET_DARK_MODE` ao car-setting) leva essa flag. No emulador: `adb shell am force-stop <package>` reproduz.
- **Memória dos outros apps:** no Android 10+ um app comum só vê a própria memória, e no 14+ não encerra os outros (`killBackgroundProcesses` só vale para si). A aba Memória pergunta a cada app da suíte (`MEMORY_QUERY`); apps de fora só aparecem se a ROM liberar, e o Encerrar deles abre a tela do app no Android ("Forçar parada").
- **Drawable de tema em código:** `setBackgroundResource(R.drawable.panel)` pega o XML (sempre o tema escuro). Em código use `ThemeDrawables.drawable(ctx, R.drawable.…)`.
- **Apps de fábrica abrindo ao ligar o carro:** com o build pessoal os players de fábrica continuam instalados e abrem a própria tela com `com.nwd.ACTION_BACKGROUND_MEDIA_PLAY`. O launcher (`start/StartGuard`, opção `start_guard`) reabre a última tela da suíte por ~10 s depois desse aviso, do boot ou da saída do ACC, e para no primeiro toque. Um app comum não sabe qual app está na frente: não tente vigiar o tempo todo.
- **Modo desenvolvedor:** diagnósticos, último erro e o menu Avançado só aparecem com `/sdcard/LAUNCHER_DEV_MODE.txt` (`DevMode.enabled()`).
- **Aliases:** os apps que substituem originais têm um `activity-alias` com o nome da Activity original (ex.: `com.nwd.radio.home_horizontalActivity`), porque o sistema e outros apps abrem por esse componente. Não remova.

## Como trabalhar

- Mudança que afeta mais de um app quase sempre vai no `common`. Verifique se o contrato entre apps (tabela acima) continua igual.
- Ao mudar um package ou um contrato, atualize `build.gradle.kts`, `Apps.java`, os manifests e os `SPEC.md`/`README.md` afetados.
- Antes de commitar, rode `./gradlew assembleRelease` e procure nomes proibidos: `grep -rIil "madeira\|garagem\|gdm" --exclude-dir=build --exclude-dir=.git .` (só pode aparecer `com.garagemdomadeira.nwd.launcher` no `build.gradle.kts` e na documentação dele).
- Commits em português, na `main`, só com os arquivos da tarefa. Pode haver trabalho em andamento de outra pessoa no repositório: confira `git status` e não inclua o que não é seu.

## Onde está cada coisa

| Assunto | Arquivo |
|---|---|
| Tabela de packages, assinatura | `build.gradle.kts` |
| Packages no código, actions da suíte | `common/.../Apps.java` |
| Configuração compartilhada | `common/.../config/LauncherConfig.java`, `NwdConfigs.java` |
| Dock e navegação | `common/.../ui/DockView.java`, `DockNavigator.java`, `Splash.java` |
| Tema, fonte, idioma, densidade | `common/.../ui/Ui.java`, `Palette.java`, `ThemeInflater.java`, `ThemeDrawables.java` |
| Uma fonte por vez | `common/.../audio/PlayerHandoff.java` |
| Rádio (cliente do serviço, favoritas) | `common/.../radio/RadioClient.java`, `PresetStore.java` |
| Bluetooth | `common/.../bt/BtClient.java` |
| SettingService (volume, EQ, brilho) | `common/.../system/NwdSettings.java`, `common/.../eq/SettingClient.java` |
| Tela inicial (grade de módulos) | `common/.../home/HomeGrid.java`, `launcher/.../home/HomeModuleViews.java` |
| Tela de navegação (mapa integrado ou app externo em tela dividida + música) | `launcher/.../NavHomeActivity.java`, `launcher/.../nav/`, `common/.../home/NavApps.java` |
| Mapa, busca, rota, voz | `map/` (ver `map/SPEC.md`) |
| Radares, limites de velocidade (dados das vias) | gerador `tools/road-data/`, workflow `.github/workflows/road-data.yml`, app `map/.../road/` |
| Mapa offline (desenhado pelo app) | gerador `tools/road-data/MapDataBuilder.java`, mesmo workflow, app `map/.../vector/` (download pelo `road/RoadSync`) |
| Indicador de RAM/CPU | `common/.../system/SysMonitor.java` |
| Aba Memória (uso por app, encerrar) | `car-setting/.../system/MemoryPanel.java`, `common/.../system/AppMemory.java` |
| Copiar APKs para o pen drive | `copy-apks.sh` |
