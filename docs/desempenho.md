# Desempenho

Meta: rodar liso numa central com **2 GB de RAM e 4 núcleos de 1,5 GHz** (SoC ARM de entrada, GPU modesta).

## Regras que o código segue

- **Nada de tique por segundo sem necessidade.** O relógio da tela inicial mostra horas e minutos, então redesenha na virada do minuto. Indicadores (voltímetro, RAM/CPU) atualizam a cada 2–10 s e só com a tela na frente.
- **`setText` só quando o texto muda.** No Android, `setText` mede e diagrama de novo mesmo com o texto igual.
- **Animação só enquanto algo se mexe**, a ~30 quadros por segundo (o mapa usa `Choreographer` com 20 ms de espera, ou seja, um vsync sim e outro não). Posição de GPS repetida não anima.
- **Nada desenhando escondido.** Com a tela do mapa atrás de outra (navegando), o mapa não pede quadros; a voz e o GPS continuam.
- **Atualizações em excesso são filtradas na origem.** Os players de música mandam a posição várias vezes por segundo; a `MediaSessions` só avisa a tela quando muda faixa, artista, capa, play/pausa ou app.
- **Memória contada.** Tiles do mapa em RGB_565 (128 KB), no máximo 32–72 em RAM conforme `getMemoryClass()`, liberados ao sair da tela sem navegar; disco limitado (200 MB, apaga os mais antigos).
- **Trabalho pesado fora da thread principal**, com prioridade de fundo: download e decodificação de tiles (2 threads, LIFO, descarta o que saiu da tela), busca e rota.
- **Reconexão com calma.** Serviço da NWD que nem existe no aparelho é procurado a cada 60 s, não a cada 15 s.

## Como foi medido

- Emulador Android 10 x86_64 com **2 GB de RAM** (`MemTotal` 2,0 GB) e **4 núcleos**, 1280×720, APKs de **release** (R8, não depuráveis).
- Script com `adb`: CPU do processo pelo `/proc/<pid>/stat` (root no emulador), quadros pelo `dumpsys gfxinfo` (total, com atraso, percentis), memória pelo `dumpsys meminfo` (PSS). Perfil com `simpleperf record -e cpu-clock`.
- GPS simulado com `adb emu geo fix <lon> <lat> <alt> <sat> <velocidade>` a cada segundo ao longo de uma rota real (ver [Ambiente local](ambiente-local.md#simular-o-gps)).

### Limite do método

Não dá para reproduzir no emulador um processador de 1,5 GHz com GPU própria. Tentei prender o emulador a 1 e a 2 núcleos do host: os quadros passaram de 600 ms, mas porque a emulação de vídeo (que no emulador roda na CPU do host) ficou sem CPU. Com o emulador livre, a mesma tela não teve nenhum quadro com atraso. Por isso os números abaixo servem para **comparar antes e depois** e para achar desperdício (CPU parado, quadros a mais, memória), não para prever o tempo de quadro na central. Na central, use o indicador de RAM/CPU da tela inicial.

## Resultados

| Cenário | CPU (de 1 núcleo virtual) | Quadros | Com atraso | p50 / p90 / p99 | Memória (PSS) |
|---|---|---|---|---|---|
| Tela inicial parada | 0,3% | — | — | — | 31 MB |
| Gaveta de apps rolando | 1,8% | 76 em 14 s | 0 | 5 / 5 / 5 ms | 29 MB |
| Tela de navegação, mapa parado | 0,3% | — | — | — | 39 MB |
| Arrastando o mapa | 7,6% | 268 em 12 s | 0 | 5 / 5 / 5 ms | 45 MB |
| Zoom do mapa | 6,1% | 136 em 8 s | 0 | 5 / 5 / 6 ms | 50 MB |
| **Navegando** (mapa girando, 50 km/h) | **11,0%** | 886 em 30 s (29,5 qps) | 0 | 5 / 5 / 6 ms | 48 MB |

Antes e depois das otimizações:

| O quê | Antes | Depois |
|---|---|---|
| Tela inicial parada (thread principal + pool, APK de debug) | 5,0 ticks/s | 1,8 ticks/s (−63%); release: 0,3% de CPU |
| Navegando: quadros por segundo | ~56 (a trava de 30 qps não funcionava) | 29,5 |
| Navegando: CPU | 18,8% | 11,0% (−41%) |
| Mapa com a tela atrás de outra | continuava animando | não desenha |

Suíte inteira aberta e parada (20 s): **0,0% de CPU em todos os processos**. Memória: launcher 46 MB (com o mapa já usado), configurações 33 MB, rádio 29 MB, MP3 28 MB, equalizador 25 MB, música Bluetooth 24 MB, cerca de 186 MB ao todo.

No perfil navegando, mais da metade das amostras é `read_hpet` (ler o relógio), que é cara só no emulador x86 virtualizado; numa central ARM essa leitura é quase de graça. O resto se divide entre a thread de desenho (51%) e a principal (46%), sem função do app em destaque.

## Indicador de RAM e CPU

Configurações › Principais › **Mostrar memória e CPU** (ligado por padrão). Fica ao lado do voltímetro, na tela inicial, e atualiza a cada 2 s (`common/.../system/SysMonitor.java`):

- **RAM**: `ActivityManager.MemoryInfo` (sempre).
- **CPU**, na ordem: uso real pelo `/proc/stat` (o Android 8+ costuma bloquear para apps, mas algumas ROMs liberam); senão a frequência atual dos núcleos em relação à máxima (`cpufreq`, legível na maioria das centrais ARM; é uma aproximação da carga); senão só a RAM aparece. No emulador nenhum dos dois é legível, então só a RAM foi vista.

## O que medir na central

1. Ligar o indicador e anotar RAM e CPU na tela inicial parada, na tela de navegação parada e navegando.
2. `adb shell dumpsys gfxinfo com.garagemdomadeira.nwd.launcher` depois de navegar alguns minutos: "Janky frames" e os percentis.
3. `adb shell dumpsys meminfo com.garagemdomadeira.nwd.launcher`: PSS total.
