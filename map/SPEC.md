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
| `MapScreen` | a tela inteira: `MapView` + busca, prévia da rota (Ir/Cancelar), faixa da manobra, chegada, zoom/centralizar, velocidade; liga GPS, voz e rede |
| `MapView` | o mapa desenhado no Canvas: tiles com zoom fracionado e rotação, rota (`drawLines`), alfinete, seta do carro, gestos, modo navegação (mapa girando, carro no terço de baixo, zoom pela velocidade), tema escuro por `ColorMatrix` |
| `TurnIcon` | seta da manobra (Path numa grade 24×24) |
| `MapConfig` | `/sdcard/K4811/nav/nav.config.json` (servidores, cache, zoom máximo) e `online()` |
| `Recents` | `K4811/nav/recent.json`, últimos 8 destinos |
| `geo/Geo` | Web Mercator (mundo 0..1), distância, rumo, projeção num segmento |
| `tiles/Tiles` | camadas memória → `.mbtiles` → disco → internet; uma instância por processo |
| `route/Router` | rota OSRM (`polyline6`, `steps=true`, `bearings` para não começar com retorno) |
| `route/Places` | busca Nominatim (1 por segundo, só ao confirmar; caixa de ~100 km em volta) |
| `route/Route` | linha (lat/lon e mundo), distância acumulada, manobras (`Step`) |
| `route/Navigator` | progresso na rota: projeção, próxima manobra, restante, saída da rota (3 posições a > 40 m + precisão), chegada (< 25 m), momentos dos avisos |
| `route/Phrases` | textos das manobras e distâncias (strings `map_*` do common, 8 idiomas) |
| `route/Voice` | `TextToSpeech` com `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` e foco `GAIN_TRANSIENT_MAY_DUCK`; `say` interrompe (manobras), `sayAfter` entra na fila (alertas); o foco só é devolvido ao fim da última frase |
| `RoadSign` | placa do limite (círculo branco com borda vermelha, cores fixas de placa) ou ícone do alerta (radar, semáforo, lombada, cancela, cruz de ferrovia) |
| `road/RoadSync` | mantém os dados das vias atualizados (regras abaixo); estado em `K4811/nav/roads/state.json` |
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

## Memória e CPU (central de 2 GB, 4 × 1,5 GHz)

- Tiles RGB_565 (128 KB cada), no máximo 32–72 em memória conforme `getMemoryClass()`; ao sair da tela sem navegar, `Tiles.trimMemory()`.
- 2 threads de download/decodificação com prioridade de fundo; pedidos LIFO (o que acabou de aparecer vem primeiro); pedido não renovado por 20 quadros é descartado; falha de rede espera 20 s para o mesmo tile.
- Enquanto o tile certo não chega, desenha um pedaço ampliado de um tile de zoom menor que já está em memória.
- Animação a ~30 qps só enquanto algo se mexe (`Choreographer`, espera de 20 ms entre quadros); posição repetida não anima; janela invisível (tela atrás de outra) não pede quadros.
- Rota: pontos a menos de 2,5 dp do anterior são pulados e trechos inteiros fora da tela não vão para o desenho.

## Offline

- Tiles: os do cache em disco e os de arquivos `.mbtiles` em `K4811/nav/` (SQLite com PNG/JPG, numeração TMS), lidos antes da internet.
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
- O tema escuro é um filtro sobre os tiles claros do OSM, não um estilo de mapa próprio.
