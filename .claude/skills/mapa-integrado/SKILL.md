---
name: mapa-integrado
description: Regras para mexer no mapa integrado da suíte (módulo map/, MapScreen, MapView, tela de navegação do launcher) com seus dois provedores, OpenStreetMap e Google. Use sempre que a tarefa tocar no mapa, na busca de endereços, na rota, na voz das manobras, nos alertas de radar e limite, nos tiles, nas opções de navegação do car-setting ou em qualquer serviço do Google Maps Platform, mesmo que o pedido fale só de um dos provedores ou pareça uma mudança pequena de layout.
---

# Mapa integrado (OpenStreetMap e Google)

O mapa integrado é uma tela só (`map/.../MapScreen` + `MapView`) com dois provedores escolhidos nas Configurações (`nav_map_provider`): **OpenStreetMap** (padrão, funciona sem internet) e **Google** (tiles, busca e rota do Google, só com internet e dentro das cotas grátis). Leia antes o `AGENTS.md`, o `map/SPEC.md` e o `docs/plano-mapa-google.md`.

## 1. Os dois provedores se comportam igual

O motorista não pode ter que reaprender a tela ao trocar de provedor. Toda mudança de layout ou comportamento (botões, faixa da manobra, prévia, busca, gestos, zoom, tema, linha da rota, alertas, voz) vale para os dois e é conferida nos dois.

Quando não der para manter igual, por limite técnico, termos de uso ou custo, **pare e pergunte ao dono** antes de implementar: explique a diferença, por quê, e as opções. Exemplos de casos que já exigiram pergunta: voz numa rota do Google, alertas que o Google não oferece.

## 2. Termos do Google (o que derruba a chave se for violado)

- Conteúdo do Google (rota, lugares, tiles) **só sobre os tiles do Google**. Nunca sobre o OpenStreetMap, o mapa offline próprio ou os `.mbtiles`.
- Dados do OSM sobre o mapa do Google podem: radares, limites, rota do OSRM ou do grafo, endereço do toque longo.
- **Voz:** frases próprias da suíte (`Phrases`, strings `map_*`), só com o tipo da manobra e a distância. O nome da rua e o texto da instrução do Google nunca vão para o TextToSpeech; podem aparecer só na tela.
- **Cache:** coordenadas do Google por até 30 dias; `place_id` sem limite; tiles só pelo `Cache-Control`. Nada de baixar em massa nem guardar para uso offline.
- Atribuição sempre visível: "© OpenStreetMap" no OSM; o `copyright` do viewport e "Google" no Google.

## 3. Custo: só dentro das cotas grátis

- Toda chamada ao Google passa pelo `GoogleQuota` (contador mensal no fuso de Los Angeles, limite em ~90% da cota grátis) e pelo cache antes. Sem cota, cai para o próximo da cadeia; nunca chama mesmo assim.
- Cadeia da rota: Google com trânsito (Pro) → Google sem trânsito (Essentials) → OSRM → grafo baixado.
- Busca: Autocomplete com token de sessão, Place Details só com campos Essentials (`location`, `formattedAddress`, `shortFormattedAddress`). Nada de Text Search (é Pro). Antes de pedir um campo novo, confira a tabela de SKUs: um campo errado na field mask muda o preço.
- Preços e termos mudam: confira as páginas oficiais (skill `deep-research`) antes de mudar uma cota ou um campo.

## 4. Escolha do provedor

- Decidida ao abrir a tela (e ao voltar a ela), **nunca no meio de uma navegação**.
- Google só com internet, chave, cota de tiles e sem erro recente (403/429 desligam por 10 min). Senão, OpenStreetMap, com o motivo mostrado uma vez.
- Radares, limites, lombadas, pedágios e passagens de nível vêm só dos dados das vias (OSM) nos dois provedores.

## 5. Regras de sempre do projeto

Java puro, sem bibliotecas (nem o Maps SDK do Google), targetSdk 19, strings nos 8 idiomas no `common`, `launcher.json` gravado só pelo car-setting, a chave da API nunca no git nem nos APKs publicados. Este ambiente pode não ter o Android SDK: diga o que não foi compilado nem testado na central.
