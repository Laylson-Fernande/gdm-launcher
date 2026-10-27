# SPEC · launcher

Leia antes o [`AGENTS.md`](../AGENTS.md) e o [`common/SPEC.md`](../common/SPEC.md).

| | |
|---|---|
| applicationId | `com.garagemdomadeira.nwd.launcher` (ao lado do `com.android.launcher` original) |
| Pacote Java | `com.k4811.launcher` |
| Application | `LauncherApp` (SuiteApp + `PlayerHandoff.init` + `NwdSettings.init`) |
| Orientação | Home `nosensor` (tem layout vertical via grade); Apps `sensorLandscape` |

## Responsabilidade

Tela inicial e gaveta de apps. **Não toca nada**: rádio, MP3, BT, telefone, EQ e ventoinha são apps separados. O launcher só mostra estado e manda comandos.

## Componentes

| Componente | Tipo | Intents |
|---|---|---|
| `HomeActivity` | Activity (singleTask, clearTaskOnLaunch) | `MAIN` + `HOME` + `DEFAULT` + `LAUNCHER` |
| `AppsActivity` | Activity (exportada) | `com.k4811.launcher.action.APPS` |
| `NavHomeActivity` | Activity (exportada, singleTask, `resizeableActivity`, tarefa própria) | `com.k4811.launcher.action.NAV_HOME` |
| `nav/MediaListener` | NotificationListenerService (vazio) | só para o Android liberar `getActiveSessions` depois que o usuário ligar o acesso a notificações |
| `overlay/VolumeOverlayService` | Service (exportado, só a prévia em modo dev) | aviso de volume por cima dos apps (SettingService tipo 14); `com.k4811.launcher.VOLUME_PREVIEW` |
| `overlay/EdgeMenuService` | Service | menu Voltar \| Vol− Vol+ \| Início ao deslizar de uma borda (`McuKeys`) |

## Tela inicial

- Grade de 6 áreas (`HomeGrid`), com o módulo de cada área vindo de `LauncherConfig.homeLayout`. `HomeModuleViews` posiciona e ajusta cada módulo ao tamanho da área.
- Módulos: Tocando agora, Relógio, Volume (`VolumeClient`/`MuteClient`), Ventoinha (lê `mcu_smart_fan_switch`), Bluetooth (`BtClient`), Imagem (`HomeImage`, sem moldura), Velocidade e Dados do GPS (`GpsClient`, só com a tela aberta).
- Barra superior: dia/noite (`NwdSettings.headlightsOn`), voltímetro (`com.nwd.action.ACTION_QUERY_CAN_BATTERY_VOLTAGE` → `ACTION_CAN_BATTERY_VOLTAGE`, extra `extra_voltage_value` = volts × 10), indicador da fonte.
- Apagar a tela: `com.nwd.action.ACTION_SET_BACKLIGHT_SWITCH` (`extra_backlight_switch`); `screen_off_after` liga isso por inatividade.
- Recria a tela no `onResume` se idioma, tema ou fonte mudaram (`lookKey`).
- Se o `launcher.json` não existia ao abrir (na partida o sdcard monta depois do launcher), espera o arquivo aparecer (`ACTION_MEDIA_MOUNTED` e conferência a cada 2 s por 2 min) e recria a tela; a pergunta de launcher padrão espera isso.
- Pergunta para virar o launcher padrão (`persist.nwd.launcher.default`) se `ask_default_launcher`.
- Segurar a área vazia abre as Configurações do Android (saída de emergência).

## "Tocando agora" (`home/HomeSession`)

| Fonte | Estado | Comandos |
|---|---|---|
| Rádio | `RadioClient` (só observando) | memória ±, liga/desliga via `PlayerHandoff.switchToRadio` |
| MP3 | `com.k4811.action.MUSIC_STATE` (e `com.android.music.metachanged/playstatechanged` do player da NWD, com `MusicBean`) | `MusicRemote.send` (`MUSIC_COMMAND`) |
| BT | `BtClient.query` + `com.bt.ACTION_BT_MUSIC_PLAY/PAUSE`, `ACTION_AVRCP_MUSIC_ID3` | `BtClient.playControl` |
| Outro app (`Kind.APP`: Spotify, YouTube Music…) | `nav/MediaSessions` (MediaSession do player; exige o acesso a notificações) | `MediaController` (ou teclas de mídia) |
| Chamada | `com.bt.ACTION_BT_*_CALL/NUMBER/NAME` | `BtClient.answer/hangup` |

A fonte vem de `mcu_current_source`. Tocar no cartão abre o app da fonte (`DockNavigator.navigate`, sem Splash; no `APP`, o próprio player).

Com a fonte Android, o cartão é do outro app quando ele está tocando e nem o MP3 da suíte nem o Bluetooth estão; sem nada da suíte com faixa, fica o último player do outro app. O selo no alto mostra o nome do app. As sessões da suíte e do sistema (`com.android.bluetooth`, telefone) são ignoradas. Sem `mcu_current_source` (aparelho que não é a central, emulador) só aparece o outro app.

## Botões

Dock → apps (ver common). Engrenagem → `Apps.SETTINGS`. Bloco da ventoinha → `Apps.FAN`. Bloco do Bluetooth e cartão de chamada → `Apps.PHONE`.

## Tela de navegação (`NavHomeActivity` + `nav/`)

"Home 2". Dois modos (`nav_mode`), decididos ao abrir e conferidos ao voltar (`checkMode`, que reabre numa tarefa nova se mudou):

- **Mapa integrado** (`activity_nav_home_map`): o painel (informação e música) de um lado (`nav_panel_right`), a `MapScreen` do módulo `map` do outro e o dock embaixo (`DockNavigator.attachDock`, item Navegação aceso; o Início do dock chama `goHome`). Navegando, o GPS e a voz continuam com a tela atrás de outra.
- **App externo**: o app de navegação numa metade da tela dividida e o painel na outra, com atalhos (Início, reabrir a navegação, Apps).

No `auto`, o integrado vale sem internet (`MapConfig.online`) ou sem app de navegação instalado. Aberta pelo item Navegação do dock e pelas Configurações.

- **Tela dividida (`nav/SplitScreen`):** `ActivityOptions.setLaunchWindowingMode(SPLIT_SCREEN_PRIMARY)` por reflexão (API oculta, liberada para targetSdk antigo: "greylist"). O Android só divide ao **criar** uma tarefa; uma que já existe vem para a frente sem mudar de modo. Por isso a tela fecha a própria tarefa e se reabre na metade primária (`SplitScreen.enter`, extra `EXTRA_ENTERING`). Quando a tela divide (`onMultiWindowModeChanged`/`onResume`), abre o app de navegação com `FLAG_ACTIVITY_LAUNCH_ADJACENT`. O Android 10 põe a metade primária à esquerda e ignora o lado pedido. Sem divisão em 1,5 s, avisa e abre o app em tela cheia.
- **Voltar ao início:** com a tela dividida, `finishAndRemoveTask()`: a metade primária fica vazia, o Android desfaz a divisão e o app de navegação continua em tela cheia atrás da tela inicial. Abrir a HOME com a divisão ativa deixaria o app como uma faixa de 8 px na borda.
- **Música (`nav/MediaSessions`):** `MediaSessionManager.getActiveSessions` (Android 5+), escolhendo o player que está tocando (ou o mais recente); faixa, artista, capa e comandos pela `MediaController`. Exige o acesso a notificações ligado para o `MediaListener` (`NavApps.mediaAccess`); sem ele, o painel mostra o botão Permitir (abre essa tela do Android) e os botões mandam teclas de mídia (`AudioManager.dispatchMediaKeyEvent`) ao último player que tocou.
- O painel atualiza entre `onStart` e `onStop`: na tela dividida a Activity fica pausada enquanto o motorista usa o mapa. Um `ContentObserver` em `enabled_notification_listeners` percebe o acesso liberado com a tela aberta.
- O app de navegação não expõe a rota: Waze e Maps não publicam manobra, distância ou chegada para outros apps (ver `docs/tela-de-navegacao.md`).

## Desempenho

- O relógio da tela inicial redesenha na virada do minuto (antes era a cada segundo) e só chama `setText` quando o texto muda; o mesmo para o indicador de RAM/CPU (a cada 2 s, só com a tela na frente).
- `MediaSessions` só avisa quando muda o que a tela mostra (os players mandam a posição várias vezes por segundo).
- Indicador `sys_monitor` na barra de cima (`show_sys_monitor`): RAM sempre; CPU por `/proc/stat` ou pela frequência dos núcleos, quando a central deixa ler (ver `SysMonitor`).

## Gaveta (`AppsActivity` + `home/AppCatalog`)

Todos os apps numa grade de 5 colunas que rola para baixo até o último (sem páginas), com entrada em cascata, ícone que afunda ao toque e o app abrindo com zoom a partir do ícone. Esconde teclados, o próprio launcher e apps cujo hardware não existe (bits de `Settings.System factory_feature`).

## Por cima dos apps (`overlay/`)

Começam no `LauncherApp`. Janelas `TYPE_SYSTEM_ALERT` (`SYSTEM_ALERT_WINDOW`, concedida na instalação), escala própria pelo tamanho real da tela.

- **Aviso de volume:** pílula no alto (alto-falante, barra na cor do tema, número) quando o volume muda; some em ~2 s; não pega toques. Ignora os valores dos primeiros 2 s (volume atual). Opção `show_volume_overlay`.
- **Menu da borda:** área de 30 de profundidade no meio da borda (380 de largura embaixo, 300 de altura nas laterais) com um puxador visível; deslizar 24 para dentro abre a pílula (deitada embaixo, em pé nas laterais). Voltar e Vol± = teclas falsas da MCU (`common/system/McuKeys`); Início = intent HOME. Segurar Vol± repete. Opção `edge_menu_side` (0 desligado, 1 embaixo, 2 esquerda, 3 direita), conferida a cada 3 s. Embaixo, a barra da NWD (`com.nwd.statusbarbottom`) fica por cima e pega o gesto em apps de fora.

## Regras

- Não grave configuração: o launcher só lê `launcher.json`.
- Comando para outra fonte sempre pela API do app dela (MusicRemote, BtClient, RadioClient + PlayerHandoff).

## Lacunas

- A tela de navegação não entra na regra "uma fonte por vez": Spotify/YouTube Music não pausam quando o rádio liga (o `PlayerHandoff` só conhece os players da suíte).
- A proporção da tela dividida é do Android (o usuário arrasta o divisor); não há API para fixá-la.
- Não substitui o launcher original: os serviços dele (janela de volume, barra lateral, botão flutuante, clima) continuam no `com.android.launcher`.
