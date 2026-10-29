# SPEC · car-setting

Leia antes o [`AGENTS.md`](../AGENTS.md) e o [`common/SPEC.md`](../common/SPEC.md).

| | |
|---|---|
| applicationId | `com.nwd.car.setting` (ao lado do `com.android.car.setting` original) |
| Pacote Java | `com.k4811.car.setting` |
| Application | `SettingApp` (SuiteApp + `NwdSettings.init` + `LauncherConfig.ensureExists` + `AudioGains.reapply`) |
| Orientação | todas as telas `nosensor` |

## Responsabilidade

**Único app que grava o `/sdcard/nwd-configs/launcher.json`.** Também ajusta a central pelo SettingService e por props. Aberto pela engrenagem do launcher.

## Telas

| Activity | Conteúdo |
|---|---|
| `SettingsActivity` | painéis numa coluna lateral: Principais (idioma, dia/noite, voltímetro, memória e CPU, apagar a tela), Customizar (modo escuro, tema, fonte, dia e noite: farol ou horário e o que muda, atalhos para as três telas abaixo), Assistências (câmera de ré, navegação), Sistema (gravar log para diagnóstico, suíte na frente ao ligar, bloquear notificações, launcher padrão, barra da NWD, bipe, volume ao ligar, brilho, "Sobre"), Memória (`system/MemoryPanel`: RAM em uso e apps rodando, do que mais usa ao que menos, com Encerrar; lê só ao abrir a aba, em Atualizar e depois de encerrar), Áudio (escondido), Avançado (só modo desenvolvedor, com senha) |
| `HomeSettingsActivity` | mapa da tela inicial (`HomeMapView`): tocar no módulo e depois na área; escolher a imagem da pasta `K4811/` |
| `DockSettingsActivity` | ícones do dock (até 5), tamanho do dock (`dock_size`), app do botão Música (`music_app`) e, só com o ZLINK instalado, nome do Z-Link, conectar automaticamente (`zlink_auto_connect`) e abrir automaticamente (`zlink_auto_open`) |
| `NavSettingsActivity` | navegação, em seções: tela de navegação (abrir, mapa integrado/app externo/automático, lado da música, informação, acesso à música), mapa integrado (voz, zoom dinâmico, perspectiva, mapa offline com o estado lido de `K4811/nav/maps/state.json`), áudio (música durante a navegação e volume das notificações, em %, via `audio/MixVolumes`), radares e alertas (radares, limite de velocidade, outros avisos da via, período dos dados das vias, com o estado lido de `K4811/nav/roads/state.json`) e app externo (app de navegação) |
| `AdvancedActivity` | região do rádio, freio de mão, ligar com qualquer tecla, props de áudio/microfone, menus da NWD, câmera 360 |
| `DevPropsActivity` | lista e edita props do sistema, exporta para Download/ |

## Onde cada opção grava

| Destino | Como |
|---|---|
| `launcher.json` | `LauncherConfig.set…` |
| MCU (brilho, volume inicial, bipe, ré, região do rádio…) | `NwdSettings` (AIDL `SettingFeature`) e `Settings.System mcu_*` |
| Props (`persist.*`) | `SystemProps.requestSet` + `PropRows` (confere em 1,5 s e mostra o comando adb se não aplicar) |
| App de navegação | `Settings.System navigation_packagename/classname/appname` |

`DarkModeReceiver` (exportado): `com.k4811.action.SET_DARK_MODE` com `on`, pedido pelo interruptor da barra de brilho do launcher; e pela troca de período do `DayNightWatcher`; grava `dark_mode` (vale o último pedido). Quem envia põe `FLAG_INCLUDE_STOPPED_PACKAGES`: a NWD dá force-stop no car-setting, e parado ele não recebe o pedido sem essa flag.

UI das listas: `ui/OptionList` (linhas de toggle, escolha, stepper, ação), `ui/PropRows`, `ui/InputDialog`, `ui/ThemeSwatches`.

## Regras

- Ao mudar idioma, tema ou fonte, `restartScreens()` recria só este app; o launcher se recria sozinho ao voltar.
- O botão "Usar o K4811" grava `persist.nwd.launcher.default = Apps.LAUNCHER`.
- Toda opção nova: string nos 8 idiomas e, se for compartilhada, chave em `LauncherConfig` (ver common).

## Lacunas (fora por enquanto)

Lista branca, restaurar fábrica, atualização do sistema, voz, luz de 3 cores, informação do dispositivo, extra `ref0`. Exigem a chave de plataforma ou não foram portados; continuam no Config. Veículo original.
