# SPEC · bt-music

Leia antes o [`AGENTS.md`](../AGENTS.md) e o [`common/SPEC.md`](../common/SPEC.md).

| | |
|---|---|
| applicationId | `com.nwd.bt.music` (por cima do original) |
| Pacote Java | `com.k4811.bt.music` |
| Application | `com.k4811.common.SuiteApp` |
| Activity | `BtMusicActivity` (singleTask, `sensorLandscape`, fora dos recentes) + alias `com.nwd.bt.music.home_horizontalActivity` |

## Responsabilidade

Mostra a faixa do celular e manda play/pause/anterior/próxima. O áudio sai do celular via A2DP; quem fala com o chip é o serviço de BT da NWD (`common/bt/BtClient`).

## Componentes

| Componente | Papel |
|---|---|
| `BtMusicActivity` | tela; `claimSource` (`ACTION_APP_IN_OUT` app 15); escuta `com.bt.ACTION_A2DP_ESTABLISHED/RELEASE`, `ACTION_BT_MUSIC_PLAY/PAUSE`, `ACTION_AVRCP_MUSIC_ID3` (`extra_avrcp_id3_title/artist`), `ACTION_AVRCP_ESTABLISHED` |
| `BackgroundPlayReceiver` | `com.nwd.ACTION_BACKGROUND_MEDIA_PLAY` com `extra_app_id` = 15 → `BtResumeService` |
| `BtResumeService` | espera o BtClient conectar (até 10 s), `PlayerHandoff.beforeBt`, `claimSource`, `playControl(4)` (play forçado) e para |

`BtClient.playControl`: 0 play, 1 pause, 2 próxima, 3 anterior, 4 play forçado.

## Regras

- Antes de tocar: `PlayerHandoff.beforeBt` (pausa o MP3).
- Pareamento e conexão ficam no app de telefone (botão abre `Apps.PHONE`).
