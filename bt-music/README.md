# GDM K4811 App suite · Música BT

Substituto da tela de música Bluetooth (A2DP/AVRCP) da central NWD, com o **mesmo package** do original (`com.nwd.bt.music`), com o visual padrão do GDM K4811 App suite.

> Especificação técnica (para quem vai mexer no código, inclusive agentes de IA): [`SPEC.md`](SPEC.md).

| | |
|---|---|
| Package | `com.nwd.bt.music` (o mesmo do original) |
| Versão | 2.0.0 (versionCode 20270000, maior que o do original) |
| App original | `radio apk/com.nwd.bt.music` |

**Dock e configurações:** usa o mesmo dock do launcher e segue as configurações feitas no [`car-setting`](../car-setting/README.md) (idioma, tema, fonte, ícones do dock). Ver [`../common`](../common/README.md).

## Código

| | |
|---|---|
| Linguagem | **Java puro**, Views clássicas e layouts XML, **sem nenhuma biblioteca** |
| minSdk / targetSdk | **17 / 19**, os mesmos do original |
| Tamanho do APK | ~710–740 KB (release; a maior parte são as 6 fontes) |

```
bt-music/src/main/
├── AndroidManifest.xml          BtMusicActivity (singleTask, launcher, fora dos recentes como o original)
├── java/com/k4811/bt/music/
│   └── BtMusicActivity.java     faixa, artista, aparelho, play/pause, anterior e próxima
└── res/layout/activity_bt_music.xml
```

O áudio sai do celular. O app só conversa com o serviço de Bluetooth da NWD pelo `BtClient` do [`common`](../common/README.md) e escuta os broadcasts `com.bt.ACTION_A2DP_*`, `ACTION_BT_MUSIC_*` e `ACTION_AVRCP_*`.

## Fonte de áudio

- Ao abrir a tela e a cada comando, avisa a central que a música BT é a fonte (`ACTION_APP_IN_OUT` app 15, como o original). Também envia `com.k4811.action.PAUSE_PLAYERS`, que faz o music pausar.
- Quando o rádio ou o MP3 viram a fonte, eles pausam a música BT pelo `BtClient` (`PlayerHandoff`). Por isso este app não precisa de receiver de pausa.
- O botão de telefone abre o phone (`com.nwd.android.phone`), onde ficam o pareamento e a conexão.

## Diferenças do original

Activity com o nome do original (`activity-alias`), para o sistema e outros apps continuarem abrindo este app pelo mesmo componente.

**Retomar ao ligar o carro:** com `com.nwd.ACTION_BACKGROUND_MEDIA_PLAY` e `extra_app_id` = 15, o `BackgroundPlayReceiver` inicia o `BtResumeService`. Ele espera a conexão com o serviço de BT da NWD (até 10 s), pausa o MP3, avisa a central que a música BT é a fonte (app 15) e manda o "play forçado", como a tela faz ao conectar no A2DP. Depois para, sem abrir a tela. Ainda não testado na central.

## Build e instalação

```bash
./gradlew :bt-music:assembleRelease
adb install -r bt-music/build/outputs/apk/release/bt-music-release.apk
```

Status: compila (debug e release). Ainda não testado na central.
