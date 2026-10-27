# Documentação

Guias para quem vai desenvolver, testar ou gerar os APKs da suíte K4811.

| Documento | Conteúdo |
|---|---|
| [Visão geral](visao-geral.md) | O que o projeto faz, os apps, a arquitetura e como eles conversam |
| [Ambiente local e testes](ambiente-local.md) | Pré-requisitos, configurar o SDK, compilar, instalar e testar (emulador e central) |
| [Gerar o APK](gerar-apk.md) | Build de release, assinatura, versão, conferência do APK, cópia para o pen drive e CI |
| [Tela de navegação](tela-de-navegacao.md) | A "Home 2": mapa integrado (OpenStreetMap) ou app externo em tela dividida, música e informação; decisões, validação e como testar |
| [Desempenho](desempenho.md) | Regras para central de 2 GB / 1,5 GHz, medições no emulador, indicador de RAM/CPU |

Outros documentos do repositório:

- [`AGENTS.md`](../AGENTS.md): regras do projeto (leitura obrigatória antes de mexer no código).
- `<app>/SPEC.md`: especificação técnica de cada app. `<app>/README.md`: descrição para pessoas.
- [`releases/`](../releases/): notas de cada versão (o CI exige `releases/<versão>.md`).
