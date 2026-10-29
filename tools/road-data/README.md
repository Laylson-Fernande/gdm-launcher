# Dados das vias (radares, limites de velocidade) e mapa offline

Geradores dos dados que o mapa integrado baixa: `RoadDataBuilder` (limite de velocidade da via, radares, lombadas, pedágios e passagens de nível; ver `map/SPEC.md`, "Dados das vias") e `MapDataBuilder` (o mapa offline, abaixo). Leem um recorte do OpenStreetMap (`.osm.pbf`) e gravam quadrados de 0,25° (~28 km) que o launcher baixa aos poucos.

Java puro, sem bibliotecas: o leitor de `.pbf` (`Pbf.java`) decodifica o protobuf à mão. Não faz parte do build dos apps.

## Uso

```bash
javac -encoding UTF-8 -d build/road-data tools/road-data/*.java
java -Xmx5g -cp build/road-data RoadDataBuilder brazil-latest.osm.pbf site/roads "Geofabrik brazil 2026-10-25"
```

Saída em `site/roads/v1/`: `index.json` e `cells/<cx>_<cy>.bin`. Recortes em <https://download.geofabrik.de/> (Brasil inteiro ou uma região, como `south-america/brazil/sudeste-latest.osm.pbf`).

| Recorte | Tempo (PC) | Memória | Saída |
|---|---|---|---|
| Sudeste (862 MB) | 26 s | 730 MB | 1.383 quadrados, 26 MB |
| Brasil (2,1 GB) | 68 s | 2,1 GB | 7.846 quadrados, 64 MB; índice de 204 KB (~85 KB compactado) |

No Brasil (outubro de 2026): 10.110 radares (299 de semáforo), 92 mil lombadas, 956 pedágios, 14 mil passagens de nível; 483 mil vias com limite. Para referência: 100 km em volta de São Paulo = 56 quadrados, 4,5 MB; em volta de BH, 2 MB.

## Publicação (GitHub Pages)

O workflow `.github/workflows/road-data.yml` roda toda segunda (e à mão, em Actions → Road data → Run workflow): baixa o Brasil da Geofabrik, gera os quadrados e publica no GitHub Pages do repositório. O app monta o endereço sozinho no build (`https://<owner>.github.io/<repo>/roads/v1/`, ver `build.gradle.kts`).

Uma vez só, no repositório: **Settings → Pages → Build and deployment → Source: GitHub Actions**. Se o repositório já publica outra coisa no Pages, o deploy deste workflow substitui o site inteiro; nesse caso, use outro servidor e aponte `road_data` no `nav.config.json` da central.

## O que entra

| Dado | Do OSM |
|---|---|
| Vias (limite por sentido, mão única) | `highway=motorway…tertiary` (e `_link`) sempre; `unclassified`, `residential`, `living_street` só com limite. `maxspeed`, `maxspeed:forward/backward` (número ou mph); `oneway`, rotatória e `motorway` implicam mão única |
| Radar | `highway=speed_camera`; relações `type=enforcement` (`maxspeed`, `average_speed`, `traffic_signals` = radar de semáforo), que trazem limite e sentido (`from`→`to`) |
| Sentido do radar | `direction` (graus ou N/NE/…); se o radar é um nó de uma via de mão única, o sentido da via; com `direction=forward/backward`, o da via no ponto |
| Lombada | `traffic_calming=bump/hump/table/cushion/dip/yes`, só em nó de via de carro |
| Pedágio | `barrier=toll_booth`, em nó de via de carro |
| Passagem de nível | `railway=level_crossing`, em nó de via de carro |

Limites implícitos (`BR:urban` etc.) não são convertidos: via sem número fica sem placa.

## Formato

`index.json`:

```json
{"format":1,"generated":1793095653142,"generated_iso":"…","source":"…","license":"ODbL 1.0, (c) OpenStreetMap contributors",
 "cell_deg":0.25,"cells":{"533_265":"117c625e:838108", "…":"…"}}
```

`cells` = nome do quadrado → `crc32 hexadecimal:tamanho em bytes`. Quadrado `cx_cy`: `cx = floor((lon + 180) / 0.25)`, `cy = floor((lat + 90) / 0.25)`.

Quadrado (`.bin`): `K4RD`, versão (1 byte, hoje 1) e o resto em deflate (zlib):

```
int cx, int cy
varint nVias
  por via: byte classe (1 motorway … 8 living_street, +16 nas _link), byte mão única (0 dupla, 1 no sentido, 2 contra),
           byte limite ida, byte limite volta (km/h, 0 = desconhecido), varint nPontos,
           coordenadas em 1e-6 grau, zigzag varint: a primeira relativa ao canto sudoeste do quadrado, as outras à anterior
varint nPontos
  por ponto: byte tipo (1 radar, 2 radar de semáforo, 3 lombada, 4 pedágio, 5 passagem de nível), byte limite,
             short sentido (graus, -1 = qualquer), lat, lon (zigzag varint, relativas ao canto)
```

Uma via que cruza quadrados vai inteira em cada um deles. O quadrado não tem data: o mesmo dado gera o mesmo arquivo (e o mesmo CRC), então o app só baixa de novo o que mudou. Mudou o formato? Suba a versão do byte e o `format` do índice, e o leitor (`map/.../road/RoadCell.java`) junto.

## Mapa offline (`MapDataBuilder`)

O mapa que o app desenha sozinho quando os dados estão baixados (ver `map/SPEC.md`, "Mapa offline"): ruas com nome, rios, água, mar e nomes de lugares. Prédios, vias pequenas, verde, limites, trilhos e córregos ficam de fora dos arquivos (`omitted`; a tabela abaixo descreve como seriam): o app não os desenha (padrão do `map_hide`, por desempenho na central) e assim o download e o site ficam com menos da metade do tamanho. Para voltar com uma camada, tire-a de `omitted`, gere de novo e tire-a do `map_hide`. Os tiles do OpenStreetMap não servem para isso: a política deles proíbe baixar áreas para usar offline.

```bash
java -Xmx12g -cp build/road-data MapDataBuilder brazil-latest.osm.pbf site/maps "Geofabrik brazil 2026-10-25"
```

| Recorte | Tempo (PC) | Memória | Saída |
|---|---|---|---|
| Brasil (2,1 GB) | ~2,5 min | ~5 GB (roda com `-Xmx12g`) | 12.191 arquivos, ~382 MB; 100 km em volta de São Paulo = 80 arquivos, 22 MB |

Com todas as camadas (antes do `omitted`): Brasil ~790 MB, São Paulo 70 MB.

Saída em `site/maps/v1/`: `index.json` (mesmo formato do das vias, mais `overview_deg`) e `cells/<nome>.bin`, com os mesmos quadrados de 0,25° (`<cx>_<cy>`) e mais os quadrados de 2° da visão geral (`o<x>_<y>`, mesma conta com 2°).

### O que entra

| Dado | Do OSM | Onde |
|---|---|---|
| Vias | `highway=motorway…living_street`, `service`, `pedestrian`, `track`, `footway/path/cycleway/steps/bridleway` (e `_link`; de `service` em diante, **fora**); nome (`name`, ou `ref` nas principais); `tunnel`, `bridge` | todas nos blocos; até `residential` na seção simplificada; até `primary` na visão geral |
| Ferrovias (**fora**) | `railway=rail/narrow_gauge/preserved` (trem), `light_rail/subway/monorail`, `tram` | blocos; trem também na simplificada |
| Rios | `waterway=river/canal` (com nome) e `stream` só com nome (córregos **fora**) | blocos; rios na simplificada |
| Água | `natural=water/bay`, `landuse=reservoir/basin`, `waterway=riverbank/dock` (vias fechadas e multipolígonos) | todos os níveis (os grandes) |
| Mar | `natural=coastline` montado em polígonos (abaixo) | todos os níveis |
| Verde (**fora**) | parque (`leisure=park/garden/pitch/golf_course`, `landuse=grass/meadow/recreation_ground/village_green/cemetery`, `natural=grassland/heath`), mata (`natural=wood/scrub`, `landuse=forest/orchard`) | todos os níveis (os grandes) |
| Areia (**fora**) | `natural=beach/sand` | blocos e simplificada |
| Prédios (**fora**) | `building=*` (menos `no`), no bloco do primeiro vértice (sem recorte) | detalhe dos blocos |
| Limites (**fora**) | relações e vias `boundary=administrative` com `admin_level` 4 (estado) e 8 (município) | blocos e simplificada; estados na visão geral |
| Lugares | nós `place=city/town/village/suburb/borough/neighbourhood/quarter` com nome | blocos; até bairro na simplificada; cidades na visão geral |

**Mar:** o litoral do OSM tem a terra à esquerda. Em cada retângulo (bloco, quadrado, quadrado da visão geral), os trechos de litoral recortados são ligados pela borda no sentido horário, formando o polígono do mar; ilhas inteiras dentro viram furos. Retângulo sem litoral é mar se o centro fica à direita do trecho de litoral mais perto (busca até ~3°). Pontas soltas (a Geofabrik corta o litoral na borda do recorte) são levadas até a borda mais perto. Só os quadrados com litoral e os vizinhos deles levam mar.

### Formato

Arquivo: `K4MP`, versão (1 byte, hoje 1), tipo (0 quadrado, 1 visão geral), coluna e linha (`int`), número de seções (1 byte, sem sinal), o tamanho de cada seção (`int`) e as seções, cada uma em deflate (zlib) separado, para o app abrir só a que precisa. Quadrado: seção 0 = simplificada (zooms 10–12), 1–64 = base dos blocos 8×8 (zoom 13+; bloco `by*8+bx`, `by` de norte para sul), 65–128 = detalhe dos blocos (prédios, vias de serviço, trilhas e caminhos; zoom 15+), só quando algum bloco tem algo nelas (com essas camadas em `omitted`, o arquivo tem 65 seções). Visão geral: só a seção 0 (zooms 7–9).

Coordenadas em Web Mercator inteiro: o mundo tem 2^26 unidades (~0,6 m no equador), `x` do oeste para o leste e `y` do norte para o sul.

```
seção:
byte shift (0 nos blocos ~0,6 m, 4 na simplificada ~10 m, 7 na visão geral ~75 m)
int origemX, int origemY (canto noroeste do retângulo)
varint nNomes; por nome: varint tamanho, UTF-8
varint nFeições; por feição:
  byte tipo, byte flags (1 nome, 2 túnel, 4 ponte, 8 _link, 16 área)
  [varint nome, se flag 1]
  varint nPartes; por parte: varint nPontos e as coordenadas >> shift em zigzag varint,
  a primeira relativa à origem e as outras à anterior (sem zerar entre partes)
```

Tipos: 1 motorway, 2 trunk, 3 primary, 4 secondary, 5 tertiary, 6 unclassified/residential, 7 living_street, 8 service, 9 pedestrian, 10 track, 11 caminho; 20 trem, 21 metrô/VLT, 22 bonde; 30 rio, 31 córrego; 40 limite de estado, 41 de município; 50 água, 51 mar, 52 parque, 53 mata, 54 areia, 55 prédio; 60 cidade, 61 town, 62 vila, 63 bairro, 64 sub-bairro (um ponto). Áreas: as partes são anéis (fechados implicitamente), preenchidos com par/ímpar. Linhas e áreas que cruzam blocos são recortadas na borda (prédios não). Simplificação Douglas-Peucker: ~0,6 m em vias, trilhos e prédios; ~1,2 m em água e limites; ~2,4 m em parques e areia; ~3,6 m em matas (sem pedaços de menos de ~0,3 ha); ~12 m na simplificada, ~100 m na visão geral. Mudou o formato? Suba a versão nos dois lados (`MapDataBuilder.FORMAT` e `MapSection.FORMAT`).

### Tamanho

O GitHub Pages aceita até 1 GB publicado; dados das vias + mapa do Brasil dão ~446 MB (outubro de 2026; ~855 MB com todas as camadas), e o workflow falha antes de publicar se passar de 1.000 MB. O que mais pesa: ruas residenciais (~235 MB antes do deflate), prédios (~195 MB), matas (~180 MB). Se o OSM crescer além do limite: aumentar a simplificação das matas (`tolerance`), tirar matas da seção de detalhe, ou gerar um recorte menor (região da Geofabrik no `region` do workflow).

## Licença dos dados

Os dados gerados são derivados do OpenStreetMap: © colaboradores do OpenStreetMap, sob a [ODbL 1.0](https://www.openstreetmap.org/copyright). A publicação leva um `README.txt` com essa atribuição, e o mapa mostra "© OpenStreetMap" sempre.
