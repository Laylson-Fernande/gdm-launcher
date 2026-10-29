# SPEC · common (biblioteca)

Leia antes o [`AGENTS.md`](../AGENTS.md) da raiz.

## Responsabilidade

Tudo que mais de um app usa: dock e navegação entre apps, tema/fonte/idioma/densidade, configuração compartilhada (`launcher.json`), "uma fonte por vez", barra flutuante da NWD, clientes dos serviços da NWD (rádio, Bluetooth, SettingService), grade da tela inicial, strings dos 8 idiomas, drawables, estilos e fontes. Não tem Activity.

## Pacotes

| Pacote | Classes | Para quê |
|---|---|---|
| `com.k4811.common` | `Apps`, `SuiteApp` | packages dos apps (do `BuildConfig`) e actions da suíte; Application base (CrashLog, idioma, BottomBar, AppMemory) |
| `config` | `NwdConfigs`, `LauncherConfig` | pasta `/sdcard/nwd-configs`, gravação atômica de JSON; leitura/gravação do `launcher.json` |
| `ui` | `Ui`, `Palette`, `ThemeInflater`, `ThemeDrawables`, `DockView`, `DockNavigator`, `Splash`, `IconView`, `ButtonIcon`, `Touch` | cores, fontes, idioma, densidade 1280×720; temas OKLCH; dock; navegação; ícones vetoriais; ícone à esquerda de botão de texto (MP3, EQ); feedback de toque |
| `audio` | `PlayerHandoff`, `AudioGains` | uma fonte por vez entre apps; volume por fonte (painel escondido) |
| `home` | `HomeGrid`, `HomeImage`, `GpsClient`, `NavApps`, `MusicApps` | áreas A–F e módulos da tela inicial; imagem da pasta `K4811/`; GPS; apps de navegação e acesso à música da tela de navegação; players que o botão Música do dock pode abrir |
| `radio` | `RadioClient`, `RadioState`, `PresetStore`, `StationArt`, `Band`, `BandRange`, `Preset` | cliente do serviço de rádio da NWD; favoritas em `K4811/radio/radio.config.json`; arte das estações |
| `music` | `MusicRemote`, `AlbumArt` | contrato MP3 ↔ launcher; capa da faixa |
| `eq` | `EqModel`, `SettingClient` | modelo do EQ e cliente do SettingService (usados por eq, car-setting, AudioGains) |
| `system` | `ExternalApps`, `SystemProps`, `NwdConstants`, `NwdSettings`, `CrashLog`, `BottomBar`, `DevMode`, `McuKeys`, `SysMonitor`, `AppMemory` | apps da central (AUX, 360, vídeo, Z-Link); props; constantes da NWD; SettingService; registro de crash; barra flutuante; modo desenvolvedor; teclas falsas da MCU (Voltar, Início, Vol±); uso de RAM e CPU; memória do próprio app e "encerrar" para a aba Memória (`MEMORY_QUERY`/`QUIT`, receiver dinâmico: só responde quem está rodando) |
| `bt` | `BtClient` | serviço de Bluetooth da NWD |
| `com.bt`, `com.nwd.radio.service.data` | Parcelables | precisam ter o mesmo nome e formato do original (keep no R8) |

AIDL em `src/main/aidl`: `com.bt.*`, `com.nwd.radio.service.*`, `com.nwd.setting.service.*`. A **ordem dos métodos** precisa ser a do original.

## Manifest

Declara `READ_EXTERNAL_STORAGE` e o receiver `com.k4811.common.system.BottomBar$Query` (action `com.k4811.action.BOTTOM_BAR_QUERY`), que entra no manifest de todos os apps.

## `launcher.json` (só o car-setting grava)

| Chave | Tipo | Padrão | Uso |
|---|---|---|---|
| `version` | int | 2 | formato |
| `language` | string | `en` | `pt en es fr ru ja zh hi` (`LauncherConfig.LANGUAGES`) |
| `theme` | int | -1 | `Palette.DEFAULT` (-1) ou 0..`Palette.THEMES`-1 |
| `dark_mode` | bool | true | fundo escuro; `false` inverte luminosidade dos neutros (fundo claro). Vale a última escolha: com o tema seguindo o dia e a noite (`day_night_affects` = `theme` ou `both`), a troca de período grava escuro ao anoitecer e claro ao amanhecer (pedido do launcher, `DayNightWatcher`); entre as trocas, o que o motorista escolher. Troca perdida com a central desligada é aplicada ao ligar (último período guardado nas preferências do launcher) |
| `day_night_by` | string | `headlights` | o que decide o dia e a noite (`config/DayNight`): `headlights` (farol aceso = noite, sinal ILL) ou `time` (horários abaixo; o farol não muda nada) |
| `day_night_affects` | string | `brightness` | o que a noite muda: `brightness`, `theme` (fundo escuro à noite, claro de dia) ou `both`. Brilho com o farol: "Escurecer com o farol" da MCU (o car-setting liga só com `headlights` + brilho); por horário: o launcher (`status/DayNightWatcher`) troca entre o brilho do dia e o da noite, guardados no SharedPreferences dele |
| `night_start`, `day_start` | int (min) | 1080, 360 | começo da noite e do dia em minutos desde 00:00 (de 30 em 30); a noite pode passar da meia-noite |
| `font` | string | `Exo2` | `Exo2 Montserrat Rajdhani Outfit Inter PublicSans` (arquivos `assets/fonts/<id>-400/500/600.ttf`) |
| `dock_toggles` | array de ids | `radio music bt_music phone eq` | até 5 (`MAX_DOCK_TOGGLES`); ids de `DockView.Item` |
| `zlink_name` | int | 0 | índice em `ZLINK_NAMES` (Z-Link, CarPlay, Auto) |
| `zlink_auto_connect` | bool | true | false = o launcher desconecta o ZLINK quando ele conecta sem o usuário ter aberto o ZLINK antes (`launcher/.../home/ZlinkMonitor`) |
| `zlink_auto_open` | int | 1 | ao conectar: 0 esconde a tela do ZLINK, 1 mostra, 2 mostra só se ela estava aberta quando a central desligou (`ZLINK_OPEN_*`) |
| `hide_bottom_bar` | bool | false | esconder a barra da NWD |
| `home_layout` | string | `now_playing:left,clock:center_top,volume:center_bottom,fan:right_top,bluetooth:right_middle` | `módulo:área` separados por vírgula; ids de `HomeGrid.Module/Slot` |
| `home_image` | string | — | caminho da imagem do módulo Imagem |
| `show_voltmeter`, `show_day_night` | bool | true | barra superior da tela inicial |
| `screen_off_after` | int (s) | 0 | 0, 5, 10 ou 30 |
| `ask_default_launcher` | bool | true | perguntar na abertura do launcher |
| `music_app` | string | — | package do app que o botão Música do dock abre (Spotify, YouTube Music…); sem valor = o MP3 da suíte. Candidatos em `MusicApps.installed` (têm `MediaBrowserService` ou a categoria `APP_MUSIC`, e tela inicial). O cartão "tocando agora" do MP3 abre sempre o MP3 (`DockNavigator.navigateMp3`) |
| `nav_app` | string | — | package do app de navegação da tela de navegação; sem valor = automático (`NavApps.chosen`: app do Config. Veículo, depois Waze, Google Maps, outro que abra `geo:`) |
| `nav_info` | string | `clock` | informação acima da música na tela de navegação: `clock`, `speed` ou `none` (`LauncherConfig.NAV_INFOS`) |
| `nav_mode` | string | `auto` | mapa da tela de navegação: `integrated` (módulo `map`), `external` (app em tela dividida) ou `auto` (integrado sem internet ou sem app de navegação) |
| `nav_panel_right` | bool | false | no mapa integrado, música e informação à direita (o mapa à esquerda) |
| `nav_voice` | bool | true | instruções por voz no mapa integrado |
| `nav_cameras` | bool | true | alertas de radar (tela e voz) no mapa integrado |
| `nav_speed_limit` | bool | true | placa do limite de velocidade da via no mapa integrado |
| `nav_road_alerts` | bool | true | avisos de lombada, pedágio e passagem de nível no mapa integrado |
| `nav_auto_zoom` | bool | true | zoom dinâmico do mapa integrado navegando: aproxima perto da próxima manobra ou alerta, afasta quando estão longe (ver `map/SPEC.md`) |
| `nav_perspective` | bool | true | mapa integrado em perspectiva (câmera atrás do carro) com a direção do carro para cima |
| `nav_media_percent` | int | -1 | % do volume em que a música fica enquanto a navegação fala (0 a 100, de 10 em 10); -1 = nunca configurado, nada é aplicado. O car-setting grava e aplica na mixagem da central (`setGPSPromptVolumeWay`/`setMixedRatio`); o MP3 abaixa o próprio player para esse valor quando perde o foco com "pode abaixar" (`audio/MixVolumes`) |
| `notify_percent` | int | -1 | % do volume em que tocam as notificações (stream de notificação em X% do máximo; o volume da central vale para todo o áudio do Android). O car-setting aplica ao mudar e o launcher reaplica ao abrir; -1 = não mexe |
| `nav_offline_map` | bool | true | mapa offline do mapa integrado: baixa e desenha o mapa do raio de 100 km e das rotas (ver `map/SPEC.md`) |
| `nav_data_days` | int | 7 | período de conferência dos dados das vias (1, 7 ou 30 dias; ver `map/SPEC.md`) |
| `dock_size` | int | 100 | tamanho do dock em % (60, 75, 85, 100, 110): altura, ícones e textos; `DockView.heightDp`, e a grade da tela inicial redistribui os módulos |
| `save_logs` | bool | false | cada app grava o próprio log em `K4811/logs/<app>.log` (`LogFile`, ligado pelo `SuiteApp`) |
| `start_guard` | bool | true | ao ligar o carro, se um app de fábrica abrir sozinho, o launcher volta para a última tela da suíte (`launcher/.../start/StartGuard`) |
| `block_notifications` | bool | false | o launcher apaga as notificações dos apps assim que aparecem (`launcher/.../nav/MediaListener`, precisa do acesso a notificações); não apaga as fixas nem as de player |
| `show_sys_monitor` | bool | true | uso de RAM e CPU ao lado do voltímetro (`SysMonitor`) |

Áreas (`HomeGrid.Slot`): `left`(A), `center_top`(B), `center_bottom`(C), `right_top`(D), `right_middle`(E), `right_bottom`(F). Módulos: `now_playing`, `clock`, `volume`, `fan`, `bluetooth`, `image`, `speed`, `gps`.

Para criar uma configuração nova: chave + getter/setter em `LauncherConfig`, tela no car-setting, leitura no app que usa, linha nesta tabela.

## Dock (`DockView.Item`)

`HOME`, `NAVIGATION`, `RADIO`, `MUSIC`, `BT_MUSIC`, `PHONE`, `EQ`, `AUX`, `CAMERA_360`, `VIDEO`, `ZLINK`, `APPS`. `NAVIGATION` abre a tela de navegação do launcher (`Apps.ACTION_NAV_HOME`). Início, Navegação e Apps são fixos (fora do limite de 5). Cada item tem `pkg` (o app que abre); `isAvailable` esconde itens cujo app/hardware não existe. `DockNavigator.navigate`: na tela inicial abre na hora; dentro dos apps, RADIO/MUSIC/PHONE/BT_MUSIC/EQ passam por um `Splash` de 1 s. O app atual faz `finish()` ao trocar, menos o launcher.

## Regras

- Não chame `LauncherConfig.set…` fora do car-setting.
- `Ui.applyDesignDensity(activity)` antes do `setContentView` em toda Activity (aplica idioma e tema também).
- Nada de package de app escrito no código: use `Apps.*`.
- Strings novas nos 8 idiomas (`res/values*/strings_*.xml`).
- `BuildConfig.PKG_*` vem da tabela `packages` do `build.gradle.kts` da raiz.

## Lacunas

- `AudioGains` grava os volumes por fonte no SharedPreferences do app que chama (car-setting) e só reaplica quando ele abre; o painel Áudio está escondido.
