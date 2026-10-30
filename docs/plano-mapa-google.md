# Plano: Google no mapa integrado

Estado: **proposta** (30/10/2026). Nada implementado ainda, exceto a linha da rota percorrida (branch `mapa/linha-percorrida`), que vale para os dois provedores.

## O pedido

Usar o Google (rotas com trânsito e busca de lugares) no mapa integrado, só com as cotas gratuitas e com cache para economizar chamadas.

## O que os termos do Google permitem

Fontes: [Termos da Google Maps Platform](https://cloud.google.com/maps-platform/terms), [Termos específicos dos serviços](https://cloud.google.com/maps-platform/terms/maps-service-terms/index-20240522), [preços](https://developers.google.com/maps/billing/pricing). Conferido em 30/10/2026.

| Regra | Efeito no projeto |
|---|---|
| Conteúdo do Google não pode ser usado com um mapa que não seja do Google (3.2.3(e); Routes §11.3; Places §5.3) | Rota e busca do Google só sobre tiles do Google. Não dá para só "ligar o Google" no mapa OpenStreetMap atual |
| Não guardar conteúdo do Google, salvo o permitido (3.2.3(b)); coordenadas por até 30 dias, `place_id` sem limite (Routes §11.4, Places §5.4) | Cache em memória e cache curto em disco; favoritos e recentes guardam o `place_id` e renovam as coordenadas depois de 30 dias |
| Não baixar em massa tiles, rotas ou lugares (3.2.3(a)) | **Não existe mapa do Google offline** para apps de terceiros |
| Não usar conteúdo do Google com texto para fala (3.2.3(a)(iv)) | **Voz das manobras de uma rota do Google é um problema** (decisão pendente, abaixo) |

## Offline: o mapa do Google não funciona sem internet

- **Tiles:** só com internet. Não podem ser baixados para uso offline (não há equivalente ao mapa offline próprio nem aos `.mbtiles`).
- **Busca e rota do Google:** só com internet.
- **Os recursos offline de hoje** (mapa offline, busca no índice de endereços, rota no grafo baixado, radares e limites) usam dados do OpenStreetMap. Eles poderiam aparecer sobre o mapa do Google, mas sem internet não há mapa do Google para desenhá-los em cima: a tela ficaria vazia.

## Substituir todo o mapa pelo Google × opção nas Configurações

| | Substituir pelo Google | Opção nas Configurações (recomendado) |
|---|---|---|
| Sem internet (garagem, estrada, túnel, chip sem dados) | sem mapa; perde busca e rota offline na prática | cai no OpenStreetMap: tudo o que funciona hoje continua |
| Trânsito em tempo real e busca de estabelecimentos | sim | sim, no modo Google |
| Custo | toda abertura do mapa gasta cota; acima dela, paga | só no modo Google; sem cota, volta ao OpenStreetMap |
| Risco de suspensão da chave | o mapa inteiro para | o modo OpenStreetMap continua |
| Código | remover ~4 mil linhas (mapa offline) e manter o resto | um provedor a mais nas mesmas classes; nada removido |
| Radares, limites, lombadas | continuam (dados OSM sobre o mapa do Google) | iguais nos dois |

**Recomendação:** opção nas Configurações, com o OpenStreetMap como base e reserva. Substituir tudo troca um mapa que funciona sempre por um que depende de internet e de cota, numa central que muitas vezes fica sem dados.

## Desenho

Uma tela só (`MapScreen`/`MapView`) e um **provedor** escolhido ao abrir:

```
Configurações: Serviços do mapa = OpenStreetMap | Google
                         │
        abrir a tela ────┤ Google, com internet, chave e cota? ── sim ──► provedor Google
                         │                                        não ──► provedor OpenStreetMap
```

| Parte | OpenStreetMap (hoje) | Google |
|---|---|---|
| Tiles | mapa offline → `.mbtiles` → cache → tile.openstreetmap.org | Map Tiles API (2D, `roadmap`), estilo claro e escuro; sem mapa offline por cima |
| Atribuição | © OpenStreetMap | `copyright` do viewport do Google + "Google" |
| Sugestões ao digitar | índice baixado + Photon | Autocomplete (New) com token de sessão, mais favoritos e recentes |
| Lugar escolhido | já tem coordenadas | Place Details Essentials (`location`, `formattedAddress`, `shortFormattedAddress`) |
| Busca ao confirmar | Nominatim + índice | as sugestões do Google (do cache, sem nova chamada) |
| Toque longo | Nominatim (endereço do ponto) | igual (dado OSM sobre o mapa do Google é permitido) |
| Rota | OSRM → grafo baixado | Routes API com trânsito → sem trânsito → OSRM → grafo baixado |
| Radares, limites | dados das vias (OSM) | iguais |
| Voz | TextToSpeech | pendente (abaixo) |

O provedor nunca troca no meio de uma navegação. Ao voltar à tela, se o Google ficou indisponível, abre no OpenStreetMap e mostra o motivo uma vez ("Sem internet", "Cota do mês do Google atingida", "Chave do Google recusada").

## Cotas gratuitas e limites no aparelho

| Serviço (SKU) | Grátis/mês | Limite no aparelho | Depois |
|---|---|---|---|
| Map Tiles 2D | 100.000 | 90.000 | modo OpenStreetMap |
| Compute Routes Pro (trânsito + rumo do carro) | 5.000 | 4.500 | Essentials |
| Compute Routes Essentials (sem trânsito) | 10.000 | 9.000 | OSRM |
| Autocomplete Requests | 10.000 | 9.000 | só favoritos e recentes |
| Place Details Essentials | 10.000 | 9.000 | sem novos lugares do Google |

- Contador por mês (fuso de Los Angeles, o da cobrança) em `K4811/nav/google/usage.json`; o car-setting mostra o uso do mês.
- Limites editáveis no `nav.config.json`.
- Erro 403 ou 429 desliga o Google por 10 minutos e grava o erro para as Configurações.
- Estimativa de uso pessoal (1 h/dia dirigindo, 10 buscas/dia, 6 rotas/dia com recálculos): ~40 mil tiles, ~300 sugestões, ~300 detalhes e ~200 rotas por mês, bem abaixo das cotas.

## Cache

| O quê | Onde | Validade |
|---|---|---|
| Tiles | memória (como hoje) e disco separado do OSM | o `Cache-Control` da resposta |
| Sugestões | memória, por texto normalizado + posição arredondada (~1 km) | 1 hora |
| Lugar (`place_id` → coordenadas e endereço) | memória e `K4811/nav/google/places.json` | 30 dias |
| Rota | memória, por origem (~100 m), destino e rumo | 5 min com trânsito, 30 min sem |
| Favoritos e recentes do Google | guardam o `place_id` e a data | coordenadas renovadas por Details depois de 30 dias |

## Chave da API

- Digitada nas Configurações (`google_key` no `launcher.json`) ou no build (`k4811.googleMapsKey` no `local.properties`, fora do git). Nunca no workflow de release.
- Pedidos com `X-Android-Package` e `X-Android-Cert` (SHA-1 da assinatura), para a chave poder ser restrita ao app no Google Cloud.
- APIs a ativar no projeto do Google Cloud: Map Tiles API, Routes API, Places API (New). Recomendado: alerta de orçamento de US$ 1.

## Decisão pendente: voz com rota do Google

Os termos proíbem usar conteúdo do Google com texto para fala. Opções:

1. **Sem voz no modo Google:** só a faixa da manobra na tela (alertas de radar continuam falados, são dados OSM).
2. **Voz com frases próprias** montadas do tipo de manobra (ex.: "vire à direita em 300 metros", sem o nome da rua do Google). Ainda usa a geometria e a manobra do Google: zona cinzenta.
3. **Voz completa, por sua conta e risco.**

## Etapas

| # | Etapa | Entrega |
|---|---|---|
| 0 | Regras e skills | `AGENTS.md` (feito), contexto do projeto, skill `mapa-integrado` em `.claude/skills/` com o `skill-creator` |
| 1 | Configuração | `nav_map_provider`, `google_key`, `nav_google_traffic` no `LauncherConfig`; seção no car-setting (provedor, chave, trânsito, uso do mês, aviso dos termos); strings nos 8 idiomas; desenho das telas com o `frontend-design` |
| 2 | Base do Google | `map/.../google/`: `GoogleHttp` (cabeçalhos, field mask), `GoogleQuota`, `GoogleCache`, `GoogleKey` |
| 3 | Tiles | provedor de tiles no `Tiles` (OSM ou Google), sessão por tema, estilo escuro, cache em disco pelo `Cache-Control`, atribuição pelo viewport |
| 4 | Busca | `Places.Place` com `placeId`; Autocomplete com token de sessão; Details ao escolher; favoritos e recentes com `place_id` |
| 5 | Rota | `GoogleRoutes` (Routes API → `Route`), conversão das manobras para os tipos do OSRM, texto da instrução no passo; cadeia de reserva |
| 6 | Escolha do provedor | `MapScreen` decide ao abrir; aviso do motivo quando cai para o OpenStreetMap |
| 7 | Documentação e verificação | `map/SPEC.md`, `docs/tela-de-navegacao.md`, `common/SPEC.md`; `./gradlew assembleRelease`; teste na central nos dois provedores |

Cada etapa é um commit; as etapas 1 a 6 só ligam o Google quando a 6 estiver pronta.
