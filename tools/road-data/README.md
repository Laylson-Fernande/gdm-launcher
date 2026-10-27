# Dados das vias (radares, limites de velocidade)

Gerador dos dados que o mapa integrado usa para mostrar o limite de velocidade da via e avisar radares, lombadas, pedágios e passagens de nível. Lê um recorte do OpenStreetMap (`.osm.pbf`) e grava quadrados de 0,25° (~28 km) que o launcher baixa aos poucos (ver `map/SPEC.md`, "Dados das vias").

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

## Licença dos dados

Os dados gerados são derivados do OpenStreetMap: © colaboradores do OpenStreetMap, sob a [ODbL 1.0](https://www.openstreetmap.org/copyright). A publicação leva um `README.txt` com essa atribuição, e o mapa mostra "© OpenStreetMap" sempre.
