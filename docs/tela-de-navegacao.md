# Tela de navegação ("Home 2")

Uma segunda tela inicial, no estilo do Android Auto, com dois modos:

- **Mapa integrado** (módulo [`map`](../map/README.md)): o launcher ocupa a tela inteira, com o mapa OpenStreetMap ao lado do painel de música e acima do dock. Busca, rota, instruções passo a passo, voz, recálculo e chegada são do próprio launcher. Funciona sem internet na área baixada (100 km em volta do carro e as rotas já calculadas): mapa, busca de endereços e rota.
- **App externo**: o app de navegação (Waze, Google Maps…) numa metade da tela dividida e o painel na outra.

O painel tem:

- no topo, os mesmos indicadores da tela inicial: dia/noite (tocar abre a barra de brilho), voltímetro e RAM/CPU, cada um conforme Configurações › Principais;
- uma informação configurável abaixo deles: relógio com data, velocidade do GPS ou nada;
- a música do player ativo (Spotify, YouTube Music ou qualquer app com MediaSession): app, capa, faixa, artista, anterior / tocar-pausar / próxima;
- no app externo, atalhos: Início (volta à tela inicial atual), reabrir a navegação e Apps (no integrado, o dock faz esse papel).

Como abrir: item **Navegação** do dock (fixo, logo depois de Início) ou **Configurações › Customizar › Tela de navegação › Abrir**.

Código: `launcher/.../NavHomeActivity.java`, `launcher/.../nav/` (`SplitScreen`, `MediaSessions`, `MediaListener`), `common/.../home/NavApps.java`. Detalhes técnicos em [`launcher/SPEC.md`](../launcher/SPEC.md#tela-de-navegação-navhomeactivity--nav).

## Configurações

Em **Configurações › Customizar › Customizar navegação** (gravadas no `launcher.json`, ver [`common/SPEC.md`](../common/SPEC.md)):

| Opção | Chave | Valores |
|---|---|---|
| Mapa da navegação | `nav_mode` | `integrated` (mapa integrado), `external` (app externo), `auto` (padrão: integrado sem internet ou sem app de navegação instalado; senão o externo) |
| Lado da música | `nav_panel_right` | esquerda (padrão) ou direita; só no mapa integrado |
| Instruções por voz | `nav_voice` | ligado (padrão) ou desligado; só no mapa integrado |
| Mapa offline | `nav_offline_map` | ligado (padrão): baixa e desenha o mapa do raio escolhido e das rotas, e baixa o grafo e o índice de endereços da rota e da busca sem internet, com as mesmas regras dos dados das vias; mostra quantas áreas estão baixadas, o tamanho e a data |
| Zoom dinâmico | `nav_auto_zoom` | ligado (padrão): navegando, aproxima perto da próxima manobra ou alerta e afasta quando estão longe; desligado: zoom só pela velocidade |
| Visão em perspectiva | `nav_perspective` | ligado (padrão): com a direção do carro para cima, mapa inclinado como uma câmera atrás do carro (como no Waze); desligado: sempre visto de cima |
| Alertas de radar | `nav_cameras` | ligado (padrão); mostra e avisa por voz os radares à frente |
| Limite de velocidade | `nav_speed_limit` | ligado (padrão); placa com o limite da via ao lado da velocidade |
| Outros avisos da via | `nav_road_alerts` | ligado (padrão); lombadas, pedágios e passagens de nível, sem voz |
| Atualizar dados das vias | `nav_data_days` | todo dia, toda semana (padrão) ou todo mês, para todos os dados baixados (vias, mapa, rota, endereços); mostra quantas áreas estão baixadas, o tamanho e a data |
| Raio dos dados baixados | `nav_data_km` | 25, 50, 100 (padrão), 200, 300 ou 500 km em volta do carro; ao mudar, o launcher confere na hora |
| App de navegação | `nav_app` | Automático (app do Config. Veículo; senão Waze, Google Maps, outro que abra `geo:`) ou um app instalado |
| Informação | `nav_info` | `clock` (padrão), `speed`, `none` |
| Acesso à música | — | abre a tela "Acesso a notificações" do Android e mostra se está liberado |

O modo é decidido ao abrir a tela e conferido ao voltar a ela. No automático, perder ou ganhar a internet troca o modo na próxima vez que a tela é aberta ou volta para a frente, nunca no meio de uma navegação do mapa integrado.

## Como funciona e por quê

### Mapa integrado

Como nenhum app comum consegue mostrar a tela do Waze ou do Maps dentro de si (ver abaixo), o mapa foi feito do zero, sem bibliotecas, sobre dados abertos do OpenStreetMap. Detalhes em [`map/SPEC.md`](../map/SPEC.md):

- mapa offline próprio: o app desenha ruas, água, mar, rios e nomes a partir de dados do OSM baixados como os radares (abaixo); fora da área baixada, tiles do OSM com cache em disco e mapas `.mbtiles`;
- busca pelo Nominatim e rota pelo OSRM da FOSSGIS (instruções montadas com as strings da suíte, nos 8 idiomas);
- sugestões enquanto digita: do índice de endereços baixado (ruas com número de casa do CNEFE do IBGE, parques, escolas, bairros, cidades) e, com internet, do Photon;
- sem internet (ou com o OSRM fora do ar), a rota e o recálculo são calculados no próprio aparelho, sobre o grafo de ruas baixado;
- até 3 opções de rota na prévia, com tempo, distância e a diferença entre elas; o perfil de rotas preferidas (as ruas por onde o carro passa) faz o caminho de costume ser sugerido primeiro;
- bússola acima do zoom: direção do carro para cima (padrão; em perspectiva, com o carro no terço de baixo) ou norte para cima (visto de cima), guardado no próprio launcher;
- botão de alertas acima da bússola: liga e desliga na hora radares e avisos da via no mapa (guardado no próprio launcher; as opções das Configurações valem por baixo);
- zoom pela velocidade e, com o zoom dinâmico, pela distância até a próxima manobra ou alerta; seta apontando pela direção da rua da rota;
- voz pelo TextToSpeech do Android, com a música abaixando enquanto fala;
- recálculo depois de 3 posições seguidas fora da rota; chegada a menos de 25 m;
- radares, limite de velocidade, lombadas, pedágios e passagens de nível (abaixo).

### Radares e limites de velocidade

Os dados vêm do próprio OpenStreetMap, a mesma base que o OsmAnd usa para os alertas dele (`highway=speed_camera`, `maxspeed`, `traffic_calming`…). Consultar o OSM ao vivo não serve: o servidor público (Overpass) recusa uso automático e vive cheio, e não funcionaria sem internet. Por isso:

1. Toda semana um workflow do GitHub (`road-data.yml`) baixa o recorte do Brasil da Geofabrik e gera um **pacote offline** em quadrados de ~28 km, publicado no GitHub Pages do repositório (gerador e formato em [`tools/road-data/README.md`](../tools/road-data/README.md)).
2. A central guarda os quadrados em `K4811/nav/roads/` e usa **sem internet**.
3. Com internet, ela confere:
   - o **raio escolhido** (100 km por padrão) em volta do carro, logo depois de instalar (sem precisar abrir o mapa), no período escolhido (todo dia, toda semana, todo mês), quando o carro se afasta 30% do raio ou quando o raio muda;
   - os quadrados **ao longo de uma rota**, assim que ela é calculada, mesmo fora do raio.

   Conferir é baixar um índice pequeno (~85 KB compactado para o Brasil) e só os quadrados que mudaram.

Na tela, acima da velocidade, ficam a placa do limite e o próximo alerta. A velocidade fica vermelha quando passa 5 km/h do limite. A voz avisa cada radar uma vez ("Radar em 300 metros, limite de 60") e, acima do limite perto dele, pede para reduzir. Navegando, só contam os pontos em cima da rota; sem rota, os que estão à frente do carro.

| Exemplo | Quadrados | Tamanho |
|---|---|---|
| 100 km em volta de São Paulo | 56 | 4,5 MB |
| 100 km em volta de BH | 59 | 2,0 MB |
| Rota São Paulo → Ribeirão Preto (317 km) | 19 (10 fora do raio) | +0,5 MB |

### Tela dividida (app externo)

Um app comum não pode embutir a tela de outro app (o `ActivityView` do Android exige permissão de sistema, e os apps de mapa não aceitam ser embutidos). O caminho é a **tela dividida** do Android 7+:

1. Não há API pública para entrar em tela dividida. A suíte usa `ActivityOptions.setLaunchWindowingMode` por reflexão. Com targetSdk 19 o Android libera essa API oculta (log: `Accessing hidden method ... (greylist, reflection, allowed)`).
2. A HOME do sistema nunca fica dividida (o Android a mostra em tela cheia e minimiza o outro lado). Por isso a tela de navegação é uma Activity comum, numa tarefa própria.
3. Com targetSdk < 24 as Activities são "não redimensionáveis" e desfazem a divisão ao vir para a frente: a `NavHomeActivity` declara `resizeableActivity="true"`.
4. O Android só põe uma tarefa na tela dividida quando ela é **criada**; uma tarefa que já existe vem para a frente sem mudar de modo. A tela de navegação fecha a própria tarefa e se reabre já na metade primária; o app de navegação vai para a outra metade (`FLAG_ACTIVITY_LAUNCH_ADJACENT`).
5. Ao tocar em Início, a tela de navegação fecha a sua tarefa: o Android desfaz a divisão e o app de navegação continua rodando em tela cheia, atrás da tela inicial.

Consequências:

- **Android 12+ (a central é Android 15):** a API oculta do passo 1 é bloqueada e não existe mais "metade primária". A tela de navegação, em tela cheia, abre o app com `FLAG_ACTIVITY_LAUNCH_ADJACENT` e o próprio sistema divide. O resultado é o mesmo: painel à esquerda, app à direita, Início desfaz a divisão.
- No app externo, o painel fica sempre **à esquerda** (o Android 10 ignora o lado pedido e só põe à esquerda a tarefa que entra primeiro; o app de navegação só entraria primeiro se ainda não estivesse aberto). A opção "Lado da música" vale para o mapa integrado.
- A proporção começa em 50/50; o usuário arrasta o divisor e o Android lembra a posição. Não há API para fixar a proporção.
- Se a central não dividir a tela em 1,5 s, a tela avisa ("Tela dividida indisponível nesta central") e abre o app de navegação em tela cheia.

### Música

- O Spotify e o YouTube Music publicam uma **MediaSession** com título, artista, álbum, capa, estado e comandos. É de lá que o painel lê; nenhum dos dois manda broadcast útil (o `com.spotify.music.metadatachanged` não apareceu nos testes).
- Para ler as MediaSessions de outros apps o Android exige um `NotificationListenerService` com o **acesso a notificações** liberado pelo usuário. O `MediaListener` do launcher existe só para isso e não lê notificações. O Android mostra um aviso genérico ("poderá ler todas as notificações") ao liberar.
- Sem o acesso, o painel mostra o botão **Permitir** e os botões continuam funcionando por teclas de mídia, que o Android entrega ao último player que tocou.
- Na tela dividida, a Activity fica pausada enquanto o motorista usa o mapa. O painel atualiza entre `onStart` e `onStop` (enquanto visível), não só com o foco.
- O mesmo player aparece no módulo **Tocando agora** da tela inicial (nome do app, capa, faixa e botões), quando a fonte é o Android e ele está tocando, ou quando nada da suíte tem faixa. Rádio, MP3 da suíte e Bluetooth tocando têm prioridade.

### Navegação

Waze e Google Maps **não expõem a rota** para outros apps: a notificação do Waze durante a navegação diz só "Waze — Ativo. Toque para abrir." Por isso o painel não mostra a próxima manobra; o próprio app de navegação mostra, na outra metade.

A voz do Waze pede o foco de áudio como "orientação de navegação, pode abaixar os outros" (`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`): o Spotify abaixa o volume e não pausa.

## Validação

Feita no emulador (Android 10, 1280×720, imagem com Google Play) e, para o comportamento do Spotify e do Waze, num celular Android 15. **Nada foi testado na central NWD.**

| Cenário | Resultado no emulador |
|---|---|
| Abrir pelo dock, pelas Configurações e por `am start -a com.k4811.launcher.action.NAV_HOME` | ✅ painel à esquerda, Google Maps à direita |
| Abrir com o Maps já aberto antes | ✅ |
| Faixa, artista, capa e app (YouTube Music) | ✅ |
| Tocar, pausar, próxima pelo painel | ✅ |
| Painel estreito (divisor arrastado) | ✅ tudo cabe |
| Informação: relógio / velocidade | ✅ |
| Início: volta à tela inicial sem a faixa do mapa na borda | ✅ |
| Sem acesso à música: aviso + Permitir + tocar/pausar por tecla de mídia | ✅ |
| Liberar o acesso com o mapa em foco: o painel atualiza sozinho | ✅ |
| Trocar a faixa por fora com o mapa em foco: o painel acompanha | ✅ |
| Tocando agora da tela inicial: YouTube Music com capa, tocar/próxima, selo no alto, toque abre o app | ✅ |
| Tocando agora com a fonte em rádio (`mcu_current_source` 4 simulado): mostra o rádio; de volta a 0: o player | ✅ |
| Spotify no painel | ⚠️ não testado no emulador (login falhou); no celular ele publica a MediaSession da mesma forma |
| Waze ao lado | ⚠️ não roda no emulador x86_64 do Android 10; o fluxo é o mesmo do Maps |

Mapa integrado (emulador Android 10, 1280×720, GPS simulado ao longo de uma rota real em São Paulo):

| Cenário | Resultado no emulador |
|---|---|
| Mapa OSM ao lado da música, acima do dock (Navegação aceso); tema escuro | ✅ |
| Música à esquerda e à direita (Lado da música) | ✅ |
| Busca de endereço ("Avenida Paulista 1000") e destinos recentes | ✅ |
| Toque longo no mapa como destino | ✅ |
| Prévia da rota: linha, alfinete, tempo e distância, Ir / Cancelar | ✅ |
| Navegação: faixa da manobra com distância e rua, "Depois", chegada (hora, tempo, distância), velocidade | ✅ |
| Mapa girando com o carro, carro no terço de baixo, rota percorrida some | ✅ |
| Avisos por voz nos momentos certos (foco de áudio "pode abaixar" pedido a cada aviso) | ✅ (o emulador não toca o som) |
| Saiu da rota: recálculo automático | ✅ |
| Chegada: aviso e volta ao mapa livre | ✅ |
| Automático sem internet → mapa integrado (sai da tela dividida sozinho); com internet → app externo | ✅ |
| Sem internet: áreas já vistas vêm do cache | ✅ |
| Mapa offline `.mbtiles` | ⚠️ não testado (nenhum arquivo de teste) |

Busca e rota sem internet (emulador Android 15; dados do Nordeste e o CNEFE de Pernambuco gerados no PC e copiados para `K4811/nav/graph` e `places`, 100 km em volta de Garanhuns; GPS na Rua Francisco Branco, 1326):

| Cenário | Resultado |
|---|---|
| Sem internet, digitando "parque euc": sugestões a cada letra, o Parque Euclides Dourado primeiro | ✅ |
| "Rua Francisco Branco 1326": o número vem do CNEFE, interpolado (~10 m do ponto do IBGE no PC) | ✅ |
| Sem internet, escolher o parque: rota calculada no aparelho (2,1 km, 62 ms), "Rota calculada sem internet" | ✅ |
| Sem internet, sair da rota: recálculo no aparelho (3–6 ms cada) | ✅ |
| Com internet, "Marco Zero Recife" (fora da área baixada): sugestões do Photon | ✅ |
| Rotas longas no PC (mesmo código): Caruaru 100 km em 0,1 s, Recife 230 km em 0,2 s | ✅ |
| Dados publicados pelo workflow e baixados pelo `RoadSync` | ⚠️ não testado (o workflow ainda não rodou com os pacotes novos) |
| Prévia para a Av. Santo Antônio sem internet: 3 opções (cartões e linhas cinza), "Mais rápida", diferença "+1 min · +200 m"; tocar no cartão escolhe | ✅ |
| Dirigir (GPS simulado, ~43 km/h) pela 3ª opção sem navegar, sair da tela e buscar de novo: ela vem primeiro, como "Seu caminho de costume" (26 trechos no perfil) | ✅ |
| Com internet, trajeto curto (o OSRM deu uma rota só): as alternativas do aparelho completam as opções | ✅ |
| Perfil numa viagem real (GPS de verdade, horas de uso) | ⚠️ não testado na central |

Radares e limites (emulador; dados do Sudeste gerados no PC e servidos por um servidor local; GPS simulado a 72 km/h pela Av. 23 de Maio):

| Cenário | Resultado |
|---|---|
| Primeira abertura com internet: baixa os quadrados do raio de 100 km | ✅ 62 conferidos, 56 baixados (4,8 MB); os outros não têm dados |
| Abrir de novo dentro do prazo | ✅ nada baixado |
| Prazo vencido, nada mudou no servidor | ✅ só o índice conferido, 0 baixados |
| Prazo vencido, 1 quadrado mudou | ✅ só esse baixado |
| Rota para Ribeirão Preto (317 km): quadrados do caminho fora do raio | ✅ 19 conferidos, 10 baixados |
| Sem internet: nenhuma tentativa; dados no disco continuam valendo | ✅ |
| Internet volta com o mapa fechado: confere sozinho (receiver) | ✅ |
| Navegando: placa 60, velocidade 72 em vermelho, "Radar · 60 km/h" a ~400 m, contando até o radar | ✅ |
| Anúncio do radar e "reduza" | ✅ disparam (log); o emulador não tem motor de voz |
| Configurações: opções e "66 áreas, 5,2 MB, conferido em 27/10/2026" | ✅ |
| Sem rota (cone à frente do carro) | ✅ fora do emulador (o `geo fix` manda rumo 0°): o código real do `RoadWatcher` rodou no PC, num percurso de 16 km por São Paulo com rumo e ruído de ±3°; 0,024 ms por posição |

Android 15 (emulador `android-35/default`, 1280×720, 2 GB, 4 núcleos; o Relógio no lugar do app de navegação):

| Cenário | Resultado |
|---|---|
| Instalação com targetSdk 19 | ⚠️ o Android 15 puro recusa; com `--bypass-low-target-sdk-block` instala (a ROM da NWD libera sozinha) |
| Tela inicial, dock com 8 itens, idioma e tema pelo car-setting (gravação no `/sdcard`) | ✅ |
| Indicador de RAM **e CPU** | ✅ (no Android 10 só a RAM aparecia) |
| Mapa integrado, dados das vias baixados do GitHub Pages (56 quadrados), log em arquivo | ✅ |
| Navegando: placa 60, alerta "Radar · 60 km/h", anúncio do radar | ✅ (sem motor de voz na imagem) |
| App externo em tela dividida | ✅ depois da correção (antes: dividia, mas avisava "indisponível") |
| Automático: sem internet → mapa integrado em tela cheia; com internet, ao reabrir → tela dividida | ✅ depois da correção (antes: a tela nova ficava atrás) |
| Idioma depois de sair da tela dividida | ✅ depois da correção (antes: textos voltavam ao inglês do sistema) |
| Internet volta com o mapa fechado: confere os dados (receiver) | ✅ |
| Abrir os 8 apps | ✅ sem travamentos nem ANR |

### O que só a central confirma

- Se a ROM da NWD tem tela dividida e aceita a API usada.
- Se a tela "Acesso a notificações" existe na ROM (sem ela, dá para liberar por adb, ver abaixo).
- Fonte de áudio (`mcu_current_source`) com o Spotify tocando, e a redução de volume da NWD quando o GPS fala.
- O app de navegação escolhido no Config. Veículo (`Settings.System navigation_packagename`) sendo usado no modo Automático.
- Radares e limite com o GPS de verdade, principalmente sem rota (o rumo do GPS decide o cone), e a voz do aviso de radar.
- GPS de verdade no mapa integrado (o emulador não manda rumo; durante a navegação a seta usa a direção da rota, fora dela usa o rumo do GPS) e a voz saindo pelo alto-falante com a música abaixando.
- Desempenho real (ver [Desempenho](desempenho.md)).

## Testar no emulador

Use um AVD com **Google Play** (o Google Maps precisa dos serviços do Google), 1280×720; para o comportamento da central, Android 15 (`android-35`). Sem Google, qualquer app serve no lugar do de navegação (`"nav_app": "com.android.deskclock"` no `launcher.json`). O passo a passo do emulador está em [Ambiente local e testes](ambiente-local.md#testar-no-emulador). Depois:

```bash
# imagem com Play Store
android --sdk=<sdk> sdk install "system-images/android-35-ext15/google_apis_playstore/x86_64"
echo no | avdmanager create avd -n K4811_Play -k "system-images;android-35-ext15;google_apis_playstore;x86_64"
# ajuste o config.ini como no K4811_Android15 e acrescente PlayStore.enabled=true

# no emulador: entre com uma conta Google, instale Google Maps e um app de música e toque algo

# instale a suíte e libere o acesso à música sem passar pela tela do Android
adb install -r -g launcher/build/outputs/apk/debug/launcher-debug.apk
adb install -r -g car-setting/build/outputs/apk/debug/car-setting-debug.apk
adb shell cmd notification allow_listener com.garagemdomadeira.nwd.launcher/com.k4811.launcher.nav.MediaListener

# abrir a tela de navegação
adb shell am start -a com.k4811.launcher.action.NAV_HOME

# conferir a divisão e o player
adb shell dumpsys activity activities | grep "Stack #"     # split-screen-primary / secondary
adb shell dumpsys media_session | grep -E "package=|state=|description="
```

Para testar sem o acesso: `adb shell cmd notification disallow_listener com.garagemdomadeira.nwd.launcher/com.k4811.launcher.nav.MediaListener`.

### Radares e limites sem publicar nada

```bash
# gerar os dados de uma região (ver tools/road-data/README.md)
javac -encoding UTF-8 -d build/road-data tools/road-data/*.java
java -Xmx5g -cp build/road-data RoadDataBuilder sudeste-latest.osm.pbf /tmp/roads "teste"

# servir no PC e ligar ao emulador pelo adb (pelo 10.0.2.2 o servidor do Python corta respostas maiores)
cd /tmp/roads && python -m http.server 8765 --bind 127.0.0.1 &
adb reverse tcp:8765 tcp:8765
# em /sdcard/K4811/nav/nav.config.json, acrescente: "road_data": "http://127.0.0.1:8765/v1/"

# acompanhar
adb logcat -s K4811Road
adb shell cat /sdcard/K4811/nav/roads/state.json
```

Para forçar uma conferência, zere `index_checked` e `area_time` no `state.json` e reabra o mapa.

## Limitações conhecidas

- **Uma fonte por vez:** o Spotify e o YouTube Music não pausam quando o rádio liga; o `PlayerHandoff` só conhece os players da suíte.
- **Mapa integrado:** sem trânsito e faixas; busca e rota sem internet só na área baixada; o tema escuro dos tiles da internet é um filtro sobre o mapa claro do OSM.
- **Radares e limites:** valem o que a comunidade do OSM mapeou (fora das cidades grandes muitas vias não têm limite; radar não mapeado não aparece; radar móvel nunca). Sem rota, o alerta é por um cone à frente do carro, que pode errar em curvas fechadas e pistas paralelas. Precisa do GitHub Pages ligado no repositório (Settings → Pages → Source: GitHub Actions) e do workflow `road-data.yml` rodado uma vez.
- **Atualizar uma central que já tem o launcher:** a permissão de internet é nova nesta versão; no Android 10 ela pode só valer depois de reiniciar a central (o mapa fica cinza até lá). Instalação nova não tem o problema.
- A tecla Início física (ou a da barra da NWD) com a tela dividida aberta não desfaz a divisão: o Android a minimiza e sobra uma faixa escura fina do painel na borda esquerda da tela inicial. Tocar em Navegação no dock restaura a divisão. O botão Início do painel não tem esse problema.
- Painel sempre à esquerda; proporção controlada pelo divisor do Android.
