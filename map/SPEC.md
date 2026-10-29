# SPEC · map (biblioteca)

Leia antes o [`AGENTS.md`](../AGENTS.md) e o [`common/SPEC.md`](../common/SPEC.md).

| | |
|---|---|
| Tipo | biblioteca Android (sem Activity), usada pelo launcher |
| Pacote Java | `com.k4811.map` |
| Dependências | só `:common` e o SDK (sem bibliotecas de mapa) |

## Responsabilidade

Mapa e navegação feitos do zero sobre o OpenStreetMap, para a tela de navegação do launcher no modo "mapa integrado": tiles, busca de endereço, rota, instruções passo a passo, voz, recálculo e chegada. A tela que usa (hoje `NavHomeActivity`) põe uma `MapScreen` no layout e repassa `onStart`/`onStop`/`onBackPressed`.

## Classes

| Classe | Para quê |
|---|---|
| `NavProgress` | progresso da navegação para outras telas do processo (próxima manobra, distância, rua, chegada): o `MapScreen` publica a cada atualização e limpa ao encerrar ou fechar; a tela inicial do launcher mostra acima da hora e o painel da tela de navegação no lugar dela. Com `MapScreen.setRouteOutside(true)` o mapa, navegando, esconde a faixa da manobra e a de chegada e mostra o ✕ de encerrar no topo dos botões |
| `MapScreen` | a tela inteira: `MapView` + busca, prévia da rota (Ir/Cancelar), faixa da manobra, chegada, alertas/bússola/zoom/centralizar, velocidade; liga GPS, voz e rede |
| `MapView` | o mapa desenhado no Canvas: tiles com zoom fracionado e rotação, rota (`drawLines`), alfinete, seta do carro, gestos, orientação (direção do carro ou norte para cima), perspectiva, modo navegação (carro no terço de baixo, zoom pela velocidade e zoom dinâmico), tema escuro por `ColorMatrix` nos tiles da internet (os do mapa offline já vêm no tema) |
| `MapButton` | base dos botões redondos do mapa (mesmo círculo dos botões de zoom), conteúdo numa grade 24×24 |
| `CompassButton` | botão da bússola: agulha girando com o mapa (direção para cima) ou "N" fixo (norte para cima) |
| `AlertsButton` | botão dos alertas: câmera de radar, riscada quando desligados |
| `TurnIcon` | seta da manobra (Path numa grade 24×24) |
| `MapConfig` | `/sdcard/K4811/nav/nav.config.json` (servidores, cache, zoom máximo) e `online()` |
| `Recents` | `K4811/nav/recent.json`, últimos 8 destinos |
| `Favorites` | `K4811/nav/favorites.json`, endereços favoritos: aparecem antes dos recentes ao abrir a busca; a estrela de cada linha e a da prévia da rota salvam ou tiram |
| `geo/Geo` | Web Mercator (mundo 0..1), distância, rumo, projeção num segmento |
| `tiles/Tiles` | camadas memória → mapa offline (área toda baixada) → `.mbtiles` → disco → internet → mapa offline com só parte baixada; uma instância por processo; tile refeito (mantendo o velho na tela) quando o tema ou os dados mudam |
| `vector/MapSection` | uma seção de um arquivo do mapa offline, lida: tipos, flags, nomes, partes, coordenadas do mundo (2^26) e retângulo de cada feição |
| `vector/MapStore` | arquivos `K4811/nav/maps/cells/`, índice do servidor (o que não está nele não tem dado) e cache das seções lidas (16–48 MB conforme a memória) |
| `vector/TileRenderer` | desenha um tile a partir das seções (áreas, prédios se ligados, rios, limites, trilhos, vias com contorno, pontes, túneis, nomes de lugares e de ruas); `coverage()` diz se a área do tile está toda baixada |
| `vector/MapStyle` | cores (claro e escuro, fixas de mapa), larguras das vias por zoom e zoom de cada tipo |
| `route/Router` | rota OSRM (`polyline6`, `steps=true`, `bearings` para não começar com retorno) |
| `route/Places` | busca Nominatim (1 por segundo, só ao confirmar; caixa de ~100 km em volta; `addressdetails`). Número da casa digitado: resultados com ele primeiro; sem nenhum, busca de novo com o número antes da rua; a rua sem o número sai "aproximada" (aviso na linha: o ponto é o meio da rua). `reverse`: endereço do ponto do toque longo no mapa (rua e número), o jeito de acertar a casa que não está no mapa |
| `route/Route` | linha (lat/lon e mundo), distância acumulada, manobras (`Step`) |
| `route/Navigator` | progresso na rota: projeção, próxima manobra, restante, saída da rota (3 posições a > 40 m + precisão), chegada (< 25 m), momentos dos avisos |
| `route/Phrases` | textos das manobras e distâncias (strings `map_*` do common, 8 idiomas) |
| `route/Voice` | `TextToSpeech` com `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` e foco `GAIN_TRANSIENT_MAY_DUCK`; `say` interrompe (manobras), `sayAfter` entra na fila (alertas); o foco só é devolvido ao fim da última frase |
| `RoadSign` | placa do limite (círculo branco com borda vermelha, cores fixas de placa) ou ícone do alerta (radar, semáforo, lombada, cancela, cruz de ferrovia) |
| `road/RoadSync` | mantém atualizados os dados das vias (`get`) e o mapa offline (`maps`), com as mesmas regras (abaixo); estado em `K4811/nav/roads/state.json` e `…/maps/state.json` |
| `road/RoadSyncReceiver` | `CONNECTIVITY_CHANGE` no manifesto: internet conectou → confere o raio (posição da última vez) |
| `road/RoadStore` | arquivos `K4811/nav/roads/cells/<cx>_<cy>.bin` e cache em memória dos quadrados lidos (até 12 MB) |
| `road/RoadCell` | um quadrado lido: vias (limite por sentido, mão única) com índice de 32×32 baldes, e pontos de alerta |
| `road/RoadWatcher` | thread própria: limite da via atual e próximo alerta, a cada posição; resultado na thread principal |

## Serviços (padrão em `nav.config.json`, editável)

| Chave | Padrão | Política de uso |
|---|---|---|
| `tiles` | `https://tile.openstreetmap.org/{z}/{x}/{y}.png` | User-Agent próprio, cache, atribuição "© OpenStreetMap" sempre visível, nada de baixar em massa |
| `route` | `https://routing.openstreetmap.de/routed-car/route/v1/driving/` (FOSSGIS, API OSRM) | uso leve |
| `search` | `https://nominatim.openstreetmap.org/search` | no máximo 1 busca/s, sem autocompletar |
| `cache_mb` | 200 | tiles em `K4811/nav/cache/z/x/y.png`; acima do limite apaga os mais antigos até 80% |
| `max_zoom` | 19 | acima disso amplia o último nível |
| `road_data` | o do build (`BuildConfig.ROAD_DATA_URL`) | dados das vias; não é gravado no arquivo (só vale se alguém escrever à mão) |
| `road_data_mb` | 300 | espaço dos dados das vias; ao passar, saem os quadrados mais longe (fora do raio e da rota) |
| `map_data` | o dos dados das vias com `maps/v1/` no lugar de `roads/v1/` | mapa offline; não é gravado no arquivo |
| `map_data_mb` | 1024 | espaço do mapa offline (100 km em volta de São Paulo ≈ 22 MB); mesma regra de limpeza |
| `map_hide` | `["buildings", "paths", "green", "lines"]` | camadas do mapa offline que não são desenhadas (desempenho): `buildings` (prédios), `paths` (vias de serviço, calçadões, trilhas, caminhos), `casings` (contorno das vias), `green` (parques, matas, areia), `lines` (limites, trilhos, córregos), `road_names` (nomes das ruas), `antialias` (bordas suavizadas). Sem `buildings` nem `paths`, a seção de detalhe dos blocos nem é lida. Essas 4 camadas do padrão também não vêm mais nos dados (ver `tools/road-data/README.md`): tirá-las da lista só tem efeito com dados gerados de novo com elas Vale para os tiles desenhados depois da mudança (reabrir o launcher para refazer todos) |
| `map_log_ms` | 400 | tile do mapa offline mais lento que isso vai para o log (`K4811MapData`); 0 = todos, para medir |

Para trocar de servidor (outro de tiles, OSRM próprio), basta editar o arquivo; ele é relido quando muda.

## Dados das vias (radares, limites, lombadas…)

Gerados toda semana do OpenStreetMap por `tools/road-data` (workflow `road-data.yml`) e publicados no GitHub Pages do repositório, em quadrados de 0,25° (~28 km): `index.json` (CRC e tamanho de cada quadrado) e `cells/<cx>_<cy>.bin`. Formato em [`tools/road-data/README.md`](../tools/road-data/README.md).

- **Endereço:** `BuildConfig.ROAD_DATA_URL`, montado no `build.gradle.kts` da raiz a partir do repositório do GitHub (`GITHUB_REPOSITORY` no CI, remote `origin` no PC, ou `-ProadDataUrl=`). Vazio = não baixa nada.
- **Raio (`RoadSync.area`):** os quadrados a até 100 km do carro, conferidos quando vence o período das Configurações (`nav_data_days`: 1, 7 ou 30 dias) ou quando o carro se afasta 30 km do centro da última conferência. Gatilhos: cada posição do GPS com o mapa aberto, e o `RoadSyncReceiver` quando a internet conecta.
- **Rota (`RoadSync.route`):** ao calcular uma rota (prévia ou recálculo), os quadrados por onde ela passa, com margem de ~2 km, mesmo fora do raio.
- **Conferir:** baixa o `index.json` (no máximo 1 vez por dia, com `If-None-Match`) e só os quadrados cujo CRC mudou. Os quadrados não têm data dentro, então o mesmo dado gera o mesmo arquivo. CRC do arquivo diferente do índice = publicação no meio: baixa o índice de novo e tenta mais uma vez.
- **Sem internet:** nada é tentado; usa o que está no disco (nada é apagado por idade). Falha de rede espera 10 min antes de tentar de novo.
- **`RoadWatcher`:**
  - Limite: a via a até 25 m no sentido do carro (respeita mão única, rumo até 60° da via), preferindo a atual.
  - Navegando na rota: alertas a até 35 m (radar) ou 15 m (os outros) da linha da rota e, se o ponto tem sentido, no sentido da rota, pela distância ao longo dela.
  - Sem rota: pontos à frente num cone pela direção do carro. Um alerta já na tela tem folga maior, para não piscar.
  - Aparece a ~20 s do radar (300–800 m) ou ~12 s dos outros (150–400 m).
  - Voz uma vez por radar (dois radares a menos de 150 m contam como um) e "reduza" uma vez se estiver acima do limite a menos de 300 m.
- **Tela (`MapScreen`):** placa do limite acima da velocidade, que fica vermelha 5 km/h acima do limite; o alerta acima da placa. Opções em `launcher.json`: `nav_cameras`, `nav_speed_limit`, `nav_road_alerts`.
- **Botão de alertas** (acima da bússola): liga e desliga na hora, só no mapa, os alertas que estão ligados nas Configurações (radares, lombadas, pedágios, passagens de nível; tela e voz). A placa de limite continua. Guardado nas preferências do launcher (`k4811_map`, `alerts_on`). Some se `nav_cameras` e `nav_road_alerts` estão desligados.

## Orientação e zoom

- **Bússola** (acima do zoom): troca entre direção do carro para cima (padrão) e norte para cima. Vale seguindo o carro, com e sem rota; com o mapa solto, norte para cima endireita o mapa na hora. Guardado nas preferências do launcher (`k4811_map`, `heading_up`), não no `launcher.json` (só o car-setting grava nele). Com a direção para cima, navegando ou com perspectiva, o carro fica a 72% da altura; com o norte para cima, no meio.
- **Perspectiva** (`nav_perspective`, padrão ligada): com a direção para cima, o chão inclina 42°, como uma câmera atrás do carro olhando para a frente. Fica de cima com o norte para cima e na prévia da rota (até voltar a seguir o carro); a troca anima em 0,6 s. É uma projeção (`Matrix` com perspectiva) sobre o desenho plano: no plano (pixels do mapa já girados, relativos ao carro, y para baixo), um ponto vai para `(x / w, y·cos 42° / w)` na tela, com `w = 1 − y·sen 42° / (1,5 × altura da View)`. Perto do carro a escala é a do mapa plano; no alto da tela as coisas ficam ~0,55× e cabe ~2,4× mais chão à frente. Tiles: só os que caem no trapézio da tela; os que aparecem com menos de 0,62× vêm do zoom de cima (um tile no lugar de quatro). Uma névoa na cor do fundo no alto da tela esconde o detalhe pequeno demais. Rota cortada no plano no alto da tela; alfinete em pé; seta do carro apontando na direção projetada. Toque, arrasto e pinça convertem pela projeção inversa (o mesmo arrasto anda mais lá na frente). Os nomes nos tiles ficam inclinados junto com o chão.
- **Zoom da navegação:** pela velocidade (17,3 abaixo de 30 km/h até 15,2 acima de 90 km/h).
- **Zoom dinâmico** (`nav_auto_zoom`, padrão ligado): o foco é a próxima manobra (na rota) ou o próximo alerta na tela (radar, lombada…), o que vier antes. Longe dele, o zoom fica 0,7 nível mais afastado que o da velocidade; chegando perto, passa ao zoom em que o foco cabe em 80% do espaço à frente do carro (do carro até a faixa da manobra, medido no chão com a perspectiva; com o norte para cima, meia tela), até 17,8. Em passos de 0,1, animado em 2,5 s a cada posição. Zoom à mão (botões, pinça, toque duplo) navegando suspende o automático por 15 s.

## Mapa offline

O app desenha o próprio mapa a partir de dados baixados (gerador `tools/road-data/MapDataBuilder`, formato e conteúdo em [`tools/road-data/README.md`](../tools/road-data/README.md)), porque a política dos tiles do OpenStreetMap proíbe baixar áreas para uso offline. Opção `nav_offline_map` (padrão ligado).

- **Download:** o mesmo `RoadSync` dos dados das vias (instância `maps`), com as mesmas regras: raio de 100 km pelo período de `nav_data_days` ou ao se afastar 30 km, quadrados ao longo da rota calculada, índice com CRC e só o que mudou. Baixa também os quadrados de 2° da visão geral do raio e da rota. A cada ~5 s de download, os tiles na tela são refeitos com o que chegou.
- **Níveis:** zooms 7–9 da visão geral (2°), 10–12 da seção simplificada do quadrado, 13+ dos blocos 8×8 (~3,5 km); prédios, vias de serviço e caminhos ficam numa seção à parte do bloco, lida só do zoom 15 em diante (e só se uma dessas camadas está ligada).
- **Camadas:** prédios, vias pequenas (serviço, calçadões, trilhas, caminhos), verde (parques, matas, areia) e linhas (limites, trilhos, córregos) não são desenhados por padrão (`map_hide`), para aliviar a central, e o gerador também não os põe nos dados (`MapDataBuilder.omitted`); ficam ruas, nomes, água, mar e rios. Arquivos sem as seções de detalhe terminam nos blocos (65 seções); a seção que falta é lida como vazia. Abaixo do zoom 7, só os tiles da internet.
- **Quando usa:** com a área do tile toda baixada (todo quadrado que o índice do servidor tem está no disco), sempre o mapa próprio. Com parte baixada, o tile da internet/cache; sem ele, o próprio com o que houver. Sem nada baixado, como antes.
- **Desenho:** RGB_565 numa das 2 threads dos tiles; os blocos vizinhos numa margem de 64 dp entram também, para nomes e prédios na borda. Nome de rua no trecho reto mais longo que o comporta (até ~15° de curva), legível (nunca de cabeça para baixo em relação ao norte), sem repetir o mesmo nome perto; nomes de lugar primeiro, sem sobrepor. A posição é calculada no mundo, então um nome na borda sai metade em cada tile.
- **Tema:** cores próprias no claro e no escuro; ao trocar, os tiles são refeitos. O filtro escuro (`ColorMatrix`) só vale para os tiles da internet.
- **Tempo:** no emulador do PC, 10–30 ms por tile (pior ~90 ms no centro de São Paulo). Tile acima de 400 ms vai para o log (`K4811MapData`). Não medido na central.

## Memória e CPU (central de 2 GB, 4 × 1,5 GHz)

- Tiles RGB_565 (128 KB cada), no máximo 32–72 em memória conforme `getMemoryClass()`; ao sair da tela sem navegar, `Tiles.trimMemory()`.
- 2 threads de download/decodificação com prioridade de fundo; pedidos LIFO (o que acabou de aparecer vem primeiro); pedido não renovado por 20 quadros é descartado; falha de rede espera 20 s para o mesmo tile.
- Enquanto o tile certo não chega, desenha um pedaço ampliado de um tile de zoom menor que já está em memória.
- Animação a ~30 qps só enquanto algo se mexe (`Choreographer`, espera de 20 ms entre quadros); posição repetida não anima; janela invisível (tela atrás de outra) não pede quadros.
- Rota: pontos a menos de 2,5 dp do anterior são pulados e trechos inteiros fora da tela não vão para o desenho.

## Offline

- Mapa: o mapa offline (acima) no raio de 100 km e ao longo das rotas já calculadas com internet; fora disso, os tiles do cache em disco e os de arquivos `.mbtiles` em `K4811/nav/` (SQLite com PNG/JPG, numeração TMS), lidos antes da internet.
- Sem internet não há busca nem rota nova; uma rota já calculada continua guiando (sem recálculo).

## Regras

- Sem bibliotecas: o mapa é desenhado à mão; não troque por osmdroid/Mapbox.
- Strings visíveis no common (`strings_map.xml`, 8 idiomas); só o que não se traduz (`map_attribution`, `map_meters`…) fica só no `values/`.
- A atribuição "© OpenStreetMap" não pode sair da tela.

## Lacunas

- Sem rota nem busca offline (exigiria grafo de ruas e índice de endereços no aparelho).
- Sem faixas, trânsito nem alertas de comunidade (radar móvel, acidente): o OpenStreetMap não tem esses dados.
- Radares e limites valem o que a comunidade mapeou: fora das cidades grandes muitas vias não têm limite, e radar não mapeado não aparece.
- Sem rota, o alerta usa um cone pela direção do carro: em curvas fechadas ou em pistas paralelas pode errar.
- Nos tiles da internet (fora da área baixada), o tema escuro é um filtro sobre os tiles claros do OSM.
- O mapa offline não tem pontos de interesse (lojas, postos), números de casas, faixas nem sentido das ruas; os nomes giram com o mapa (direção do carro para cima).
