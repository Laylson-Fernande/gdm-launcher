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
| `nav/MediaListener` | NotificationListenerService | para o Android liberar `getActiveSessions` depois que o usuário ligar o acesso a notificações; com `block_notifications`, apaga (`cancelNotification`) cada notificação apagável assim que aparece e as que já estavam (ao conectar e quando a opção é ligada), menos as de player (`android.mediaSession`/`CATEGORY_TRANSPORT`) |
| `overlay/VolumeOverlayService` | Service (exportado, só a prévia em modo dev) | aviso de volume por cima dos apps (SettingService tipo 14), que também é controle: − e + (segurando, repetem), arrastar ou tocar na barra, tocar no alto-falante silencia (`MuteClient`); fica enquanto é tocado e 3 s depois. Só o cartão recebe toque (`FLAG_NOT_TOUCH_MODAL`); `com.k4811.launcher.VOLUME_PREVIEW` |
| `start/StartReceiver` + `start/StartGuard` | receiver (exportado) | `com.nwd.ACTION_BACKGROUND_MEDIA_PLAY`, `BOOT_COMPLETED` e `com.nwd.action.ACTION_MCU_STATE_CHANGE` (0→1): reabre a última destas telas em uso: tela inicial, navegação, rádio, música BT ou MP3 (gaveta, Configurações, telefone, EQ, ventoinha e apps de fora não mudam o destino) em 1,2, 3, 6 e 10 s, por cima do app de fábrica que abriu sozinho; para no primeiro toque numa tela do launcher ou quando outra tela da suíte vem para a frente. `com.k4811.action.SCREEN_SHOWN` (de cada `SuiteApp`) guarda a última tela. No Android 15 do emulador o sistema permite (BAL por componente/janela visível) |
| `status/DayNightWatcher` | um por processo (criado no `LauncherApp`) | dia/noite a cada minuto (`TIME_TICK`) e com o farol: aplica o brilho por horário; com o tema seguindo o dia/noite, na troca de período pede o modo escuro do período ao car-setting (`SET_DARK_MODE`, mesmo que já esteja assim) e, quando o arquivo muda, avisa as telas |
| `overlay/EdgeMenuService` | Service | menu Voltar \| Vol− Vol+ \| Início ao deslizar de uma borda (`McuKeys`) |

## Tela inicial

- Grade de 6 áreas (`HomeGrid`), com o módulo de cada área vindo de `LauncherConfig.homeLayout`. `HomeModuleViews` posiciona e ajusta cada módulo ao tamanho da área.
- Rota no bloco do relógio (`home/HomeRoute`): enquanto há rota, a manobra (seta), a distância, a rua e "Chegada 03:08 · 15 min · 8 km" ficam acima da hora; a hora e a data encolhem (`HomeModuleViews.setRouteShown`). Fontes: o mapa integrado (`map/.../NavProgress`, mesmo processo: a navegação continua com a tela de navegação fora da frente) e, sem ele, o app de navegação externo (`ACTION_NAV_STATE`, com `ACTION_NAV_QUERY` ao aparecer). Escuta só entre `onResume` e `onPause`. Com rota, tocar no relógio abre a tela de navegação; sem rota, a data e hora do Android. Só aparece se o módulo Relógio estiver na tela inicial.
- Módulos: Tocando agora, Relógio, Volume (`VolumeClient`/`MuteClient`), Ventoinha (lê `mcu_smart_fan_switch`), Bluetooth (`BtClient`), Imagem (`HomeImage`, sem moldura), Velocidade e Dados do GPS (`GpsClient`, só com a tela aberta).
- Barra superior (`status/StatusBar`, a mesma acima do relógio da tela de navegação): dia/noite (`DayNight.isNight`: farol ou horário; tocar abre `status/BrightnessPopup`, a barra de brilho, que grava pelo `common/.../system/Brightness`: na NWD `setDayBackLight` de 20% (`Brightness.min`, 2 de 10: em 0 a tela praticamente apaga) a `mcu_back_light_max`, vale para o modo atual; fora dela o brilho do Android. Embaixo da barra, o interruptor do modo escuro: pede ao car-setting (`Apps.ACTION_SET_DARK_MODE`), espera o `launcher.json` mudar (até 3 s) e refaz a tela; na navegação, como uma troca nas Configurações. Com o tema seguindo o dia/noite continua livre: a escolha vale até a próxima troca de período, como diz o aviso embaixo do nome), voltímetro (`com.nwd.action.ACTION_QUERY_CAN_BATTERY_VOLTAGE` → `ACTION_CAN_BATTERY_VOLTAGE`, extra `extra_voltage_value` = volts × 10), indicador da fonte.
- Apagar a tela: `com.nwd.action.ACTION_SET_BACKLIGHT_SWITCH` (`extra_backlight_switch`); `screen_off_after` liga isso por inatividade.
- Recria a tela no `onResume` se idioma, tema, modo escuro, fonte ou tamanho do dock mudaram (`Ui.lookKey`), e na hora quando o dia/noite troca o tema (`DayNightWatcher`).
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

Dock → apps (ver common). Engrenagem → `Apps.SETTINGS`. Bloco da ventoinha → `Apps.FAN`. Bloco do Bluetooth e cartão de chamada → `Apps.PHONE`. Botão redondo à direita do bloco do Bluetooth (só com o ZLINK instalado): desconectado, ícone do item ZLINK do dock e abre o ZLINK; conectado, X vermelho e desconecta (`home/ZlinkMonitor`).

### ZLINK (CarPlay / Android Auto)

`home/ZlinkMonitor` (um por processo, criado no `LauncherApp` se o ZLINK estiver instalado). O que se sabe do ZLINK 6.0.50 (`com.zjinnova.zlink`, código protegido; testado no emulador Android 11 com tradução ARM, sem celular conectado):

- **Conectado** = o ZLINK tem uma MediaSession ativa (aparece como música tocando; desconectado não há sessão). Ler as sessões exige o acesso a notificações do `MediaListener` ("Acesso à música"); sem ele o botão só abre o ZLINK.
- **Eventos**: broadcast implícito `com.zjinnova.zlink` com extra `status`: `ENTER` (app iniciou), `MAIN_PAGE_SHOW`, `MAIN_PAGE_HIDDEN`. Os de conexão não foram vistos; o monitor grava todo status no log (`K4811Zlink`).
- **Comandos** (receivers registrados pelo ZLINK em tempo de execução): `com.zjinnova.zlink.action.DISCONNECT`, `ACTION_APP_SHOW`, `ACTION_APP_HIDE` (mostrar/esconder testados), e também `POWER_ON`/`POWER_OFF` (não bloqueiam a conexão), `BACKCAR_START/STOP`, `USB_IDLE/OCCUPIED`, `EXTRA_BT_CONNECTED/DISCONNECTED`, `AA_PHONECALL_ON/OFF`, `AUDIO_SOURCE_CHANGE`, `SCREEN_SIZE_CHANGE`, `OUT_DARK_START/STOP`.
- **Conectar automaticamente** desligado: uma conexão nova é desfeita (DISCONNECT) se não foi pedida, isto é, se não veio do botão da tela inicial nem com a tela do ZLINK aberta há pelo menos 3 s.
- **Abrir automaticamente**: 1,5 s depois de uma conexão automática manda `ACTION_APP_SHOW` ou `ACTION_APP_HIDE`. "Se estava aberto" usa o último `MAIN_PAGE_SHOW/HIDDEN` (preferência `zlink_was_open`), congelado entre `SCREEN_OFF`/`SHUTDOWN` e `SCREEN_ON`.
- Não testado com celular: falta confirmar na central que a sessão some ao desconectar e que o DISCONNECT desfaz a conexão.

## Tela de navegação (`NavHomeActivity` + `nav/`)

"Home 2". Dois modos (`nav_mode`), decididos ao abrir e conferidos ao voltar (`checkMode`, que reabre numa tarefa nova se mudou):

- **Mapa integrado** (`activity_nav_home_map`): o painel (informação e música) de um lado (`nav_panel_right`), a `MapScreen` do módulo `map` do outro e o dock embaixo (`DockNavigator.attachDock`, item Navegação aceso; o Início do dock chama `goHome`). O painel tem 500 dp, para a hora e a data inteiras caberem na barra de cima durante a navegação; o mapa fica a 26 dp da borda da tela (alinhado ao dock, do lado de fora: à direita, ou à esquerda com a música à direita) e tem os cantos arredondados como os cartões (22 dp, `roundCorners`). Navegando, o GPS e a voz continuam com a tela atrás de outra.
- **App externo**: o app de navegação numa metade da tela dividida e o painel na outra, com atalhos (Início, reabrir a navegação, Apps).

No `auto`, o integrado vale sem internet (`MapConfig.online`) ou sem app de navegação instalado. Aberta pelo item Navegação do dock e pelas Configurações.

- **Tela dividida (`nav/SplitScreen`), Android 7 a 11:** `ActivityOptions.setLaunchWindowingMode(SPLIT_SCREEN_PRIMARY)` por reflexão (API oculta, liberada para targetSdk antigo: "greylist"). O Android só divide ao **criar** uma tarefa; uma que já existe vem para a frente sem mudar de modo. Por isso a tela fecha a própria tarefa e se reabre na metade primária (`SplitScreen.enter`, extra `EXTRA_ENTERING`). Quando a tela divide (`onMultiWindowModeChanged`/`onResume`), abre o app de navegação com `FLAG_ACTIVITY_LAUNCH_ADJACENT`. O Android 10 põe a metade primária à esquerda e ignora o lado pedido. Sem divisão em 1,5 s, avisa e abre o app em tela cheia.
- **Tela dividida, Android 12+ (a central de testes é Android 15):** `setLaunchWindowingMode` é bloqueada ("blocked, test-api") e o modo metade primária foi removido. A tela abre o app direto com `FLAG_ACTIVITY_LAUNCH_ADJACENT` a partir de tela cheia e o próprio sistema divide (esta tela à esquerda, o app à direita); se em 1,5 s não dividiu, avisa. `SplitScreen.restart` (trocar de modo no Automático) espera 600 ms depois de fechar a tarefa dividida, senão a tela nova abre atrás do app que ficou em tela cheia.
- **Voltar ao início:** com a tela dividida, `finishAndRemoveTask()`: a metade primária fica vazia, o Android desfaz a divisão e o app de navegação continua em tela cheia atrás da tela inicial. Abrir a HOME com a divisão ativa deixaria o app como uma faixa de 8 px na borda.
- **Música (`home/HomeSession`, o mesmo do "Tocando agora"):** rádio (estação, liga/desliga e memórias), MP3 da suíte, música Bluetooth e o player de outro app (`nav/MediaSessions`: `getActiveSessions`, faixa, capa e comandos pela `MediaController`). Tocar na capa abre o app da fonte. O acesso a notificações só é pedido (botão Permitir) quando a fonte é outro app ou nada; sem ele, os botões mandam teclas de mídia ao último player que tocou.
- **Tema, idioma, fonte, dock:** guarda `Ui.lookKey` ao abrir e, se mudou (inclusive o dia/noite trocando o tema), recria em `checkMode`; navegando no mapa integrado, espera a rota acabar.
- O painel atualiza entre `onStart` e `onStop`: na tela dividida a Activity fica pausada enquanto o motorista usa o mapa. Um `ContentObserver` em `enabled_notification_listeners` percebe o acesso liberado com a tela aberta.
- O app de navegação não expõe a rota: Waze e Maps originais não publicam manobra, distância ou chegada para outros apps (ver `docs/tela-de-navegacao.md`).
- **Mapa integrado navegando:** igual ao Waze: o cartão `nav_route` (seta `nav_route_turn`, distância, rua, chegada) fica no lugar do relógio/velocidade, lendo `map/.../NavProgress`, e a hora vai para a barra de status. O mapa fica sem a faixa da manobra e a de chegada (`MapScreen.setRouteOutside(true)`) e o ✕ vermelho de encerrar vai para o topo da coluna de botões.
- **Waze modificado (projeto recompile-optimized):** a tela própria dele publica a rota (`Apps.ACTION_NAV_STATE`); o painel mostra o cartão `nav_route` (seta, distância, instrução, "Chegada 21:31 · 16 min · 5 km") no lugar do relógio/velocidade enquanto há rota, e pede o estado atual ao abrir (`ACTION_NAV_QUERY`). Ao abrir o app, `navIntent` manda `Apps.EXTRA_DARK_MODE` (`LauncherConfig.darkMode`): o mapa e os cartões do Waze seguem o tema da suíte. Na meia tela, os cartões do Waze usam a largura toda e o carro fica entre eles. Com o painel visível (`Apps.ACTION_NAV_PANEL`, mandado no `onStart`/`onStop` e em resposta a `ACTION_NAV_PANEL_QUERY`), o Waze na tela dividida esconde a manobra e a chegada (o mapa fica livre) e põe o ✕ de encerrar no topo da coluna de botões; em tela cheia os cartões voltam. O aviso leva `Apps.EXTRA_INSTANCE`: ao dividir a tela o painel fecha e reabre, e o "saí" da instância velha chegava depois do "apareci" da nova (os cartões voltavam no Waze). Com a rota no painel, a hora e a data curta vão pequenas para a barra de status, ao lado da RAM/CPU (`nav_status_clock`; o `TIME_TICK` fica registrado sempre que a tela está visível). Testado no emulador Android 11 (com `settings put global hidden_api_policy 1`, senão a divisão por API oculta é negada e o Waze abre em tela cheia); falta a central.

## Desempenho

- O relógio da tela inicial redesenha na virada do minuto (antes era a cada segundo) e só chama `setText` quando o texto muda; o mesmo para o indicador de RAM/CPU (a cada 2 s, só com a tela na frente).
- `MediaSessions` só avisa quando muda o que a tela mostra (os players mandam a posição várias vezes por segundo).
- Indicador `sys_monitor` na barra de cima (`show_sys_monitor`): RAM sempre; CPU por `/proc/stat` ou pela frequência dos núcleos, quando a central deixa ler (ver `SysMonitor`).

## Gaveta (`AppsActivity` + `home/AppCatalog`)

Todos os apps numa grade de 5 colunas que rola para baixo até o último (sem páginas), com entrada em cascata, ícone que afunda ao toque e o app abrindo com zoom a partir do ícone. Esconde teclados, o próprio launcher e apps cujo hardware não existe (bits de `Settings.System factory_feature`).

- **Ordem:** os apps da suíte (`Apps.*`: rádio, MP3, BT, telefone, EQ, ventoinha, configurações) primeiro, depois os outros; cada grupo em ordem alfabética (`Collator`, sem acento nem maiúscula).
- **Ocultar:** segurar um app pergunta se oculta. O botão "Ocultos (N)" no alto lista os ocultos (esmaecidos); tocar num deles mostra de novo; "Concluir" ou Voltar sai da lista. Guardado no SharedPreferences do launcher (`apps` → `hidden`, chaves `pacote/classe`): só o launcher usa.
- **Cache (era o que deixava a gaveta lenta na central):** ler nome e ícone de cada app no PackageManager a cada abertura. Agora a lista fica em memória no processo do launcher e em disco (`cache/apps/apps.json` + um PNG por ícone); a gaveta mostra o cache na hora e, em segundo plano, compara com os apps instalados e só lê os novos ou atualizados (`lastUpdateTime`, ou idioma diferente). A grade só redesenha se algo mudou. No emulador: 23 apps lidos na primeira vez, 0 nas seguintes, 1 depois de instalar um app.

## Por cima dos apps (`overlay/`)

Começam no `LauncherApp`. Janelas `TYPE_SYSTEM_ALERT` (`SYSTEM_ALERT_WINDOW`, concedida na instalação), escala própria pelo tamanho real da tela.

- **Aviso de volume:** pílula no alto (alto-falante, − e +, barra na cor do tema, número) quando o volume muda; é também controle (− e + repetem segurando, a barra arrasta, o alto-falante silencia); some 3 s depois do último toque ou mudança; só o cartão pega toques. Ignora os valores dos primeiros 2 s (volume atual). Opção `show_volume_overlay`.
- **Menu da borda:** área de 30 de profundidade no meio da borda (380 de largura embaixo, 300 de altura nas laterais) com um puxador visível; deslizar 24 para dentro abre a pílula (deitada embaixo, em pé nas laterais). Voltar e Vol± = teclas falsas da MCU (`common/system/McuKeys`); Início = intent HOME. Segurar Vol± repete. Opção `edge_menu_side` (0 desligado, 1 embaixo, 2 esquerda, 3 direita), conferida a cada 3 s. Embaixo, a barra da NWD (`com.nwd.statusbarbottom`) fica por cima e pega o gesto em apps de fora.

## Regras

- Não grave configuração: o launcher só lê `launcher.json`.
- Comando para outra fonte sempre pela API do app dela (MusicRemote, BtClient, RadioClient + PlayerHandoff).

## Lacunas

- A tela de navegação não entra na regra "uma fonte por vez": Spotify/YouTube Music não pausam quando o rádio liga (o `PlayerHandoff` só conhece os players da suíte).
- A proporção da tela dividida é do Android (o usuário arrasta o divisor); não há API para fixá-la.
- Não substitui o launcher original: os serviços dele (janela de volume, barra lateral, botão flutuante, clima) continuam no `com.android.launcher`.
