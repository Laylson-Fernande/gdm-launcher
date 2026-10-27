# Mapa e navegação (map)

Biblioteca com o mapa integrado da tela de navegação do launcher: mapa do OpenStreetMap, busca de endereço, rota, instruções passo a passo com voz, recálculo quando sai da rota e chegada. Feita do zero, sem bibliotecas de mapa.

## Como usar

1. No dock, toque em **Navegação** (ou em Configurações › Customizar › Tela de navegação › Abrir).
2. Com o **Mapa da navegação** em "Mapa integrado" (ou "Automático" sem internet), o mapa aparece ao lado da música, acima do dock.
3. Toque em **Para onde?**, digite o endereço e confirme. Os últimos destinos aparecem ao tocar no campo vazio. Também dá para segurar o dedo num ponto do mapa.
4. Veja a prévia (tempo e distância) e toque em **Ir**.

Durante a navegação: a próxima manobra fica no alto (com "Depois" quando a seguinte vem logo), a chegada embaixo e a velocidade no canto. Arrastar o mapa para de seguir o carro; o botão de mira volta a seguir.

## Radares e limite de velocidade

Com o carro andando, a placa do limite da via aparece acima da velocidade, e a velocidade fica vermelha quando passa do limite. Radares, lombadas, pedágios e passagens de nível à frente aparecem acima da placa, com a distância; nos radares, a voz avisa uma vez e pede para reduzir se você estiver acima do limite.

Os dados vêm do OpenStreetMap e ficam guardados na central, então funcionam sem internet. Com internet, a central confere a área de 100 km em volta do carro (todo dia, toda semana ou todo mês, nas Configurações) e o caminho de cada rota calculada, baixando só o que mudou. Cada tipo de aviso liga e desliga em Configurações › Customizar › Tela de navegação.

## Sem internet

O mapa mostra as áreas já vistas (guardadas em `/sdcard/K4811/nav/cache`) e mapas offline em arquivos `.mbtiles` colocados em `/sdcard/K4811/nav/`. Busca e rota nova precisam de internet; uma rota já calculada continua guiando.

## Arquivos em /sdcard/K4811/nav

| Arquivo | O que é |
|---|---|
| `nav.config.json` | servidores de mapa, rota e busca, e o tamanho do cache (editável) |
| `recent.json` | últimos destinos |
| `cache/` | tiles baixados |
| `*.mbtiles` | mapas offline (opcional) |
| `roads/` | radares, limites e outros avisos (`cells/`, `index.json`, `state.json`) |

Detalhes técnicos: [SPEC.md](SPEC.md).
