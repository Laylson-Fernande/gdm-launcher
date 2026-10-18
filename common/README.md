# GDM K4811 App suite · Common

Módulo Android library usado por todos os apps: [`launcher`](../launcher/README.md), [`car-setting`](../car-setting/README.md), `radio`, `music`, `bt-music`, `phone`, `eq` e `fan`. Todos mostram o **mesmo dock**, com o mesmo tema, fonte e idioma, e **seguem as configurações feitas no car-setting** (aberto pela engrenagem do launcher).

> Especificação técnica (para quem vai mexer no código, inclusive agentes de IA): [`SPEC.md`](SPEC.md).

## Regra

O car-setting é o único app que grava as configurações. O launcher e os apps só leem. Quando o usuário muda algo, o launcher se recria ao voltar para ele, e cada app aplica a mudança na próxima vez que abre. Trocar de app pelo dock fecha o app anterior, então isso acontece sempre que se navega.

## O que todos os apps seguem

| Configuração | Chave em `launcher.json` | Onde é aplicada |
|---|---|---|
| Idioma (8: pt, en, es, fr, ru, ja, zh, hi; padrão en) | `language` | `Ui.applyLanguage`, em toda tela e no `SuiteApp` |
| Cor do tema (14 temas, derivados em OKLCH) | `theme` | `Ui.applyTheme` + `ThemeInflater` / `ThemeDrawables` |
| Fonte (Exo 2, Montserrat, Rajdhani, Outfit, Inter, Public Sans) | `font` | `Ui.regular/medium/semiBold` |
| Ícones do dock (até 5 + Início e Apps fixos) | `dock_toggles` | `DockView` |
| Nome do Z-Link no dock (Z-Link, CarPlay ou Auto) | `zlink_name` | `DockView.Item.label/icon` |
| Esconder a barra flutuante da NWD | `hide_bottom_bar` | `BottomBar` |

Só da tela inicial (também no `launcher.json`): `home_layout` (módulo de cada área), `home_image`, `show_voltmeter`, `show_day_night`, `screen_off_after` e `ask_default_launcher`.

Itens que não existem na central (AUX, 360, Vídeo, Z-Link) e apps da suíte que não estão instalados somem do dock (`DockView.Item.isAvailable`).

## `nwd-configs/` no armazenamento

`SharedPreferences` é privado de cada app, e cada app tem o seu package (os originais da NWD). Por isso as configurações compartilhadas ficam em disco:

```
/sdcard/nwd-configs/             (Environment.getExternalStorageDirectory() + "/nwd-configs")
└── launcher.json                escrito só pelo car-setting
```

1. **Um dono por arquivo.** Só o car-setting grava o `launcher.json`. Assim dois apps nunca gravam o mesmo arquivo ao mesmo tempo.
2. **Gravação atômica:** grava em `launcher.json.tmp` e depois faz `renameTo("launcher.json")`. Quem lê nunca pega um arquivo pela metade.
3. **Formato JSON** (`org.json`, já vem no Android), com versão:

   ```json
   {
     "version": 2,
     "language": "pt",
     "theme": -1,
     "font": "Exo2",
     "dock_toggles": ["radio", "music", "bt_music", "phone", "eq"],
     "zlink_name": 0,
     "hide_bottom_bar": false,
     "home_layout": "now_playing:left,clock:center_top,volume:center_bottom,fan:right_top,bluetooth:right_middle",
     "home_image": "/sdcard/K4811/fotos/carro.jpg",
     "show_voltmeter": true,
     "show_day_night": true,
     "screen_off_after": 0,
     "ask_default_launcher": true
   }
   ```

   Os ids são os de `DockView.Item.id` e `HomeGrid.Module/Slot.id`. Uma chave que falta ou um valor desconhecido usa o padrão.
4. **Leitura:** `LauncherConfig` só vai ao disco quando o `lastModified()` do arquivo muda.
5. **Sem arquivo** (car-setting nunca abriu, armazenamento indisponível ou erro de leitura): valem os padrões. O car-setting cria o arquivo na primeira vez que abre.

Com targetSdk 19 rodando no Android 10, `READ/WRITE_EXTERNAL_STORAGE` são concedidas na instalação e não existe scoped storage. A pasta é pública: não guardar nada sensível nela.

## Navegação pelo dock (`DockNavigator`)

| Item | Abre |
|---|---|
| Início | intent `HOME` (o launcher) |
| Rádio | `com.nwd.radio` |
| MP3 | `com.nwd.android.music.ui` |
| Bluetooth | `com.nwd.bt.music` |
| Telefone | `com.nwd.android.phone` |
| EQ | `com.nwd.audioset` |
| AUX / 360 / Vídeo / Z-Link | apps da central (`ExternalApps`) |
| Apps | gaveta do launcher (action `com.k4811.launcher.action.APPS`) |

Na **tela inicial (launcher)**, todo item abre o app **na hora**. Dentro dos apps, Rádio, MP3, Bluetooth, Telefone e EQ abrem depois de um **Splash** de 1 s ("Abrindo RÁDIO", com o ícone), por cima do conteúdo e com o dock visível. O app atual faz `finish()` ao trocar, para não empilhar, menos a tela inicial, que fica embaixo de tudo.

## Uma fonte por vez (`PlayerHandoff`)

Rádio, MP3 e música BT nunca tocam juntos: logo antes de uma fonte começar, as outras param.

| Antes de… | Para |
|---|---|
| ligar o rádio (`beforeRadio` / `switchToRadio`) | MP3 e BT |
| tocar o MP3 (`beforeMp3`) | BT |
| tocar a música BT (`beforeBt`) | MP3 |

- O MP3 roda em outro app. Ele pausa ao receber o broadcast `com.k4811.action.PAUSE_PLAYERS` (extra `from` = quem pediu). O `PauseReceiver` do app de música pausa direto no serviço e solta o foco de áudio na hora.
- A música BT pausa pelo serviço de BT da NWD (`BtClient`), menos quando quem chama é o próprio bt-music.
- O rádio sai sozinho com a troca de fonte que o MP3 (fonte 0) e o BT (app id 15) já pedem.
- `switchToRadio` espera 0,6 s antes de pegar a fonte quando ela era Android (MP3 ou BT podiam estar tocando), sem mostrar nada: a tela do rádio já está aberta, só o som entra depois. Se a fonte sai do Android antes de o player parar, o sistema da NWD força a parada do app do player.

## Barra flutuante da NWD (`BottomBar`)

Com "Esconder a barra inferior da NWD" ligado, a barra some enquanto qualquer app da suíte está na frente e volta nos outros apps. Cada app é um processo. Ao ir de um app da suíte para outro, o novo começa (e esconde a barra) antes de o antigo parar. Por isso, ao parar, o app espera 0,8 s e pergunta aos outros com um broadcast ordenado (`com.k4811.action.BOTTOM_BAR_QUERY`). O receiver `BottomBar$Query`, declarado no manifest deste módulo e presente em todos os apps, responde se aquele app tem tela na frente. A barra só volta se nenhum responder.

## "O que está tocando" do MP3 (`MusicRemote`)

Contrato entre o app de música e o widget da tela inicial: estado em `com.k4811.action.MUSIC_STATE`, pedido de estado em `MUSIC_QUERY` e comandos em `MUSIC_COMMAND` (`cmd`: 0 tocar/pausar, 1 próxima, 2 anterior). Detalhes no [README da música](../music/README.md).

## Modo desenvolvedor (`DevMode`)

Diagnóstico do rádio, aba de diagnóstico do EQ, painel do último erro, linhas técnicas do "Sobre" e o menu Avançado só aparecem se existir o arquivo `/sdcard/LAUNCHER_DEV_MODE.txt`. Vale na próxima tela aberta, sem reinstalar.

## Estrutura

```
common/src/main/
├── java/com/k4811/common/
│   ├── Apps.java                packages dos apps e actions da suíte
│   ├── SuiteApp.java            Application base: crashes, idioma, BottomBar
│   ├── config/                  NwdConfigs (pasta, gravação atômica), LauncherConfig (launcher.json)
│   ├── ui/                      Ui, Palette, ThemeInflater, ThemeDrawables, DockView, DockNavigator,
│   │                            Splash, IconView, Touch
│   ├── home/                    HomeGrid (áreas e módulos), HomeImage (pasta K4811/), GpsClient
│   ├── audio/                   PlayerHandoff, AudioGains (volume por fonte)
│   ├── system/                  ExternalApps, SystemProps, NwdConstants, NwdSettings, CrashLog,
│   │                            BottomBar, DevMode
│   ├── radio/                   RadioClient, PresetStore (radio.config.json), StationArt, Band…
│   ├── music/                   MusicRemote, AlbumArt
│   ├── eq/                      EqModel, SettingClient (EQ e volume por fonte)
│   └── bt/BtClient.java
├── java/com/bt/, java/com/nwd/radio/…   Parcelables dos serviços de BT e rádio da NWD
├── aidl/                        interfaces dos serviços de BT, rádio e SettingService da NWD
├── assets/fonts/                6 fontes × 3 pesos, com as licenças OFL
└── res/                         strings dos 8 idiomas (strings_*.xml por área), drawables,
                                 cores, estilos e animações
```

As strings ficam todas aqui, separadas por área (`strings_home.xml`, `strings_radio.xml`, `strings_settings.xml`…), e entram em todos os apps. O R8 tira de cada APK o que ele não usa. `strings_suite.xml` tem os textos que só existem na suíte (por exemplo, "App não encontrado").
