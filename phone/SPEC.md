# SPEC · phone

Leia antes o [`AGENTS.md`](../AGENTS.md) e o [`common/SPEC.md`](../common/SPEC.md).

| | |
|---|---|
| applicationId | `com.nwd.android.phone` (por cima do original) |
| Pacote Java | `com.k4811.android.phone` |
| Application | `com.k4811.common.SuiteApp` |
| Activity | `PhoneActivity` (singleTask, `sensorLandscape`) + alias `com.nwd.android.phone.home_horizontalActivity` |
| Permissão chave | `SYSTEM_ALERT_WINDOW` (concedida na instalação com target 19) |

## Responsabilidade

Telefone Bluetooth (HFP): discar, agenda, histórico, ajustes do BT e a chamada. O chip fica no serviço de BT da NWD (`BtClient`).

## Componentes

| Componente | Papel |
|---|---|
| `PhoneActivity` | abas Discar (teclado à esquerda; número e contatos cujo número contém os dígitos à direita), Agenda (busca por nome sem acento ou por número), Histórico, Ajustes; tela de chamada; `claimSource` (app 10); guarda `inFront`; `--ez demo true` carrega contatos de exemplo |
| `CallReceiver` (manifest) | `com.bt.ACTION_BT_INCOMING_CALL`, `OUTGOING_CALL`, `*_NUMBER`, `*_NAME`: com a tela na frente, entrega para ela (`onNewIntent`); fora dela, `CallFloatService` |
| `CallFloatService` | janela flutuante 780×220 a 48 do topo (`TYPE_SYSTEM_ALERT`), com nome/número, estado, Atender e Silenciar (só recebendo) e Desligar; some no `ACTION_BT_END_CALL` e enquanto a tela está na frente; escala própria (não tem Activity) |

Extras: `extra_phone_number`, `extra_phone_name`. Estados de chamada: 0 nenhuma, 1 recebendo, 2 efetuando, 3 em conversa.

## Regras

- A tela avisa o serviço flutuante em `onResume`/`onPause` (`CallFloatService.setPhoneInFront`).
- Não pausa os players: durante a chamada a central cuida do áudio.
- **Silenciar** (cartão flutuante, chamada tocando): fecha o cartão até a chamada ser atendida ou terminar, zera o volume do alto-falante do BT (`BtClient.muteSpeaker`, `setSpeakerVolume` do `BTFeature`; o toque vem do serviço BT da NWD) e manda `MUSIC_COMMAND` `cmd` 3 (o MP3 volta se a chamada o pausou). A chamada não é recusada. O volume volta no `BEGIN_CALL_ONLINE` (atendida no celular) ou no `END_CALL`, e fica nas preferências (`speaker_before_silence`) para voltar mesmo se o serviço morrer. Não testado na central: falta confirmar que o toque sai por esse volume.

## Lacunas

Provider da agenda `com.visualframe.btphone` do original (outros apps podiam consultar).
