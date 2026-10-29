# TODO: Android Auto pelo Open Headunit

Análise feita em 27/10/2026 sobre o Open Headunit (https://github.com/andreknieriem/open-headunit), commit `620af55`, versão `4.0.0-beta1`. Nada disto foi testado na central.

## Decisão: não embutir o protocolo no launcher

O Open Headunit fica como **app separado** (como o ZLINK), e a suíte conversa com ele por broadcast. Motivos para não portar o código:

- **Licença AGPL-3.0:** incorporar o código obrigaria a suíte inteira a ser AGPL, com o código-fonte aberto para quem usar.
- **Regra 1 do AGENTS.md:** o app é Kotlin (630 arquivos) e usa AndroidX, protobuf, Conscrypt, Play Services Nearby, Shizuku, libsu, Glide, ZXing, além de código nativo (FFmpeg, libusb).
- **Tamanho:** ~67 mil linhas fora o protobuf gerado (~17 mil de protocolo em `aap/`, ~37 mil de conexão USB/Wi-Fi em `connection/`, ~14 mil de decodificação em `decoder/`), mais ~125 mil linhas de protobuf gerado.
- **Certificado embutido:** o handshake TLS do Android Auto usa certificado e chave privada de central que vêm no repositório (`app/src/main/res/raw/cert` e `privkey`). Zona cinzenta legal: não trazer para este repositório.
- **Estabilidade:** decodificar vídeo H.264 dentro do processo do launcher, numa central de 2 GB; se o decoder travar, cai a tela inicial.

Limitação externa: o Android Auto 17.4+ bloqueia o início sem fio "legado". Sem dongle, o sem fio fica restrito ao modo nativo (Wi-Fi Direct/hotspot), ao "Start Headunit Server" das opções de desenvolvedor do Android Auto ou a root.

## Interface do Open Headunit (módulo `contract`)

Referência: `contract/README.md` e `contract/src/main/java/com/andrerinas/openheadunit/contract/HeadUnitIntent.kt`.

**Atenção:** o package e o prefixo das actions são diferentes. Usar o errado não dá erro, só não faz nada.

| | |
|---|---|
| package (applicationId) | `com.andrerinas.headunitrevived` |
| prefixo das actions de comando | `com.andrerinas.openheadunit.` |
| receiver dos comandos | `com.andrerinas.headunitrevived/com.andrerinas.openheadunit.automation.AutomationReceiver` |

### Estado da sessão (recebido)

Broadcast implícito, sem permissão: `com.andrerinas.headunitrevived.SESSION_STATE`.

| Extra | Valores |
|---|---|
| `state` | `connecting`, `connected`, `projecting`, `disconnected`, `failed` |
| `transport` | `usb`, `wifi`, `self`, `unknown` |
| `reason` | `user_exit`, `settings_restart`, `link_lost`, `phone_left`, `handshake_failed`, `peer_silent`, `connect_failed` ou vazio (tratar como texto aberto: valor desconhecido = "outro motivo") |
| `uptime_ms` | tempo de sessão, ou 0 |

`projecting` = Android Auto na tela e com vídeo. É o estado que conta como "conectado".

### Comandos (enviados ao `AutomationReceiver`, broadcast explícito)

| Action (prefixo `com.andrerinas.openheadunit.`) | Extras | Faz |
|---|---|---|
| `ACTION_CONNECT` | `ip` (opcional), `no_ui` | Com `ip`, conecta ao servidor do Android Auto na porta 5277; sem `ip`, verifica a USB. |
| `ACTION_DISCONNECT` | | Encerra a sessão. |
| `ACTION_STOP_SERVICE` | | Encerra a sessão e para o serviço. |
| `ACTION_RAISE_PROJECTION` | | Traz a tela do Android Auto para a frente. |
| `ACTION_SET_NIGHT_MODE` | `state` = `day`/`night`/`auto` | Tema dia/noite. |
| `ACTION_START_WIRELESS` / `ACTION_STOP_WIRELESS` / `ACTION_CANCEL_WIRELESS` | | Arma, para ou cancela o modo sem fio configurado. |
| `ACTION_CHECK_USB` | | Verifica a USB de novo. |
| `ACTION_QUERY_STATE` | | Responde com o estado em JSON (só com broadcast ordenado, no `resultData`). |

`no_ui` conecta sem trazer a tela para a frente (só na próxima vez).

### Música

- O app publica uma MediaSession. Com a opção **"sincronizar a sessão de mídia com os metadados do Android Auto"** ligada (vem **desligada**, chave `syncMediaSessionWithAaMetadata` no `utils/Settings.kt` dele), ela traz título, artista, álbum, duração e capa da música do celular (`AapService.applyAaMediaMetadataToSession`).
- Teclas de mídia: `com.andrerinas.headunitrevived.ACTION_MEDIA_KEYPRESS` com extra `event` (`KeyEvent`). Há também `…ACTION_KEYPRESS` para teclas em geral.

### Navegação (pendente de decisão)

`com.andrerinas.headunitrevived.NAVIGATION_UPDATE` traz:

- próxima manobra: `distance_meters`, `time_seconds`, `road`, `action_text`, `turn_number`, `turn_angle`;
- rua atual: `current_road`;
- até o destino: `total_distance_meters`, `total_time_seconds`, `estimated_arrival`.

Ele é enviado com a permissão `com.andrerinas.openheadunit.permission.NAVIGATION_UPDATE`, de nível **signature**: só recebe quem tem a mesma chave do Open Headunit. Opções:

1. Compilar o Open Headunit com a nossa chave (a AGPL permite, ele continua app separado; se modificarmos o código, temos que publicar as mudanças).
2. Ligar a opção de notificações de navegação dele (`show-navigation-notifications`) e ler as notificações pelo `MediaListener`. O conteúdo dessas notificações não foi conferido.

## Plano de implementação

1. **Monitor de espelhamento:** generalizar o [ZlinkMonitor](launcher/src/main/java/com/k4811/launcher/home/ZlinkMonitor.java) com dois "motores": ZLINK (como hoje, pela MediaSession) e Open Headunit (pelo `SESSION_STATE`, sem precisar do acesso a notificações). Detectar o app instalado no [ExternalApps](common/src/main/java/com/k4811/common/system/ExternalApps.java) e colocar o package lá, não solto no código.
   - O botão do bloco do Bluetooth abre e conecta, ou desconecta se já conectado.
   - "Conectar automaticamente" e "abrir automaticamente" (`zlink_auto_connect`, `zlink_auto_open`) passam a valer para os dois. Avaliar se as chaves do `launcher.json` mudam de nome (subir a versão do JSON e migrar).
   - No arranque, registrar o receiver e, se possível, perguntar o estado com `ACTION_QUERY_STATE` (ordenado), porque o `SESSION_STATE` só chega quando muda.
2. **Uma fonte por vez:** em `projecting`, tratar o Android Auto como fonte Android: pausar o MP3, o rádio e o BT pelo [PlayerHandoff](common/src/main/java/com/k4811/common/audio/PlayerHandoff.java). Ver como o sistema da NWD trata o áudio desse app (o rádio sai da fonte?).
3. **Música na tela inicial e na tela de navegação:** usar a MediaSession do Open Headunit pelo `MediaListener`. Orientar o usuário a ligar a opção de sincronizar os metadados.
4. **Teclas de mídia:** repassar tocar/pausar, próxima e anterior do dock para o `ACTION_MEDIA_KEYPRESS`.
5. **Dia/noite:** quando o tema escuro da suíte mudar, enviar `ACTION_SET_NIGHT_MODE`. Só o car-setting grava a configuração; quem envia é quem detecta a mudança (o launcher no `onResume`, por exemplo).
6. **car-setting:** escolha do app de espelhamento (ZLINK / Open Headunit / automático), com as strings nos 8 idiomas (`strings_settings.xml`).
7. **Documentação:** atualizar `launcher/SPEC.md`, `common/SPEC.md`, `car-setting/SPEC.md` e a lista de chaves do `launcher.json` no AGENTS.md.
8. **Depois:** navegação do Android Auto no painel da tela de navegação (ver as duas opções acima).

## Validar na central (K2401, Android 15)

- [ ] A USB da central faz o modo acessório Android (AOA), necessário para o Android Auto com cabo. O ZLINK funcionar sugere que sim, mas não é garantia.
- [ ] O Open Headunit instala e roda (ele tem targetSdk 36; a ROM da NWD não deve se opor).
- [ ] O `SESSION_STATE` chega ao launcher (receiver registrado em código, app com targetSdk 19).
- [ ] O áudio do Android Auto sai certo e não briga com a fonte da MCU (`mcu_current_source`).
- [ ] Decodificação de vídeo: hardware ou software (FFmpeg), e o desempenho na central de 2 GB.
- [ ] Sem fio: qual modo funciona com a versão do Android Auto do celular (ver a limitação do 17.4+).
