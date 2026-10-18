# GDM K4811 App suite · Rádio

Substituto do app de rádio original da central NWD, com o **mesmo package** do original (`com.nwd.radio`), com o visual padrão do GDM K4811 App suite.

> Especificação técnica (para quem vai mexer no código, inclusive agentes de IA): [`SPEC.md`](SPEC.md).

| | |
|---|---|
| Package | `com.nwd.radio` (o mesmo do original) |
| Versão | 2.0.0 (versionCode 20270000, maior que o do original) |
| App original | `radio apk/com.nwd.radio` |
| Protocolo do serviço de rádio | `radio apk/com.nwd.radio/COMANDOS.md` |
| Linguagem | **Java puro**, Views clássicas e layouts XML, **sem nenhuma biblioteca** |
| minSdk / targetSdk | **17 / 19**, os mesmos do `com.nwd.radio` original |
| Tamanho do APK | ~750 KB (release; a maior parte são as 6 fontes) |

**Dock e configurações:** usa o mesmo dock do launcher e segue as configurações feitas no [`car-setting`](../car-setting/README.md) (idioma, tema, fonte, ícones do dock). Ver [`../common`](../common/README.md).

## Estrutura

```
radio/
├── build.gradle.kts             app com.nwd.radio (código em com.k4811.radio), depende de :common
└── src/main/
    ├── AndroidManifest.xml      uma Activity (RadioActivity, singleTask, launcher, nosensor)
    ├── java/com/k4811/radio/
    │   ├── RadioActivity.java   a tela
    │   ├── RadioApp.java        SuiteApp + Bluetooth conectado
    │   └── ui/                  TunerDialView, PresetCardView
    └── res/layout/              activity_main (uma coluna, também na vertical), dialog_keypad, dialog_diag, keypad_key
```

Dock, tema, fonte, strings e ícones vêm do [`common`](../common/README.md). O cliente do serviço de rádio (`com.k4811.common.radio`: `RadioClient`, `PresetStore`, `StationArt`… e o AIDL) também fica lá, porque o launcher usa no "Tocando agora".

## Tela

Uma coluna, de cima para baixo (pensada para 1280×720, a mesma serve na vertical):

- **Dial** no alto: arraste para sintonizar à mão.
- **Estação** no centro: imagem (sem arquivo, um ícone de rádio), frequência, nome e RadioText. ⏮ ‹ à esquerda e › ⏭ à direita. ‹ › andam um passo; segurados, procuram a próxima estação com sinal naquela direção.
- **Rádio** (liga/desliga), **Stereo**, **Distante** e **Banda**, todos no mesmo desenho: ícone, texto e uma bolinha acesa quando ligado. Banda mostra a banda atual e a cada toque vai para a próxima (FM1 → FM2 → FM3 → AM1 → AM2 → FM1); segurar 2 s procura as estações da banda e grava nas memórias (substitui as que havia). Teclado numérico à direita (para sair, o dock).
- **Memórias** logo acima do dock: 12 por banda, retangulares, numa linha que rola de lado (cabem 6 e um pedaço da 7ª). Só a frequência, grande, e o número da memória pequenino no canto. Toque toca, toque longo grava.
- Diagnóstico do serviço de rádio só no modo desenvolvedor (ver `common`).

## Comportamento

- **Uma fonte por vez:** ao ligar o rádio, o MP3 (outro app) e a música BT pausam antes (`PlayerHandoff.switchToRadio`). Se a fonte era Android, o som do rádio entra 0,6 s depois (a tela abre na hora).
- Ao voltar para a tela com o rádio já tocando, ele não é religado. Na abertura, volta na última estação ouvida.
- O idioma, o tema e a fonte vêm de `/sdcard/nwd-configs/launcher.json`.

## Arquivos do rádio (`K4811/radio/`, na memória interna, nunca USB ou SD)

- Imagem 1:1 de cada estação: `FM_104.5.jpg`, `AM_1040.png`…
- `radio.config.json`: as 12 favoritas de cada banda e a última estação, que voltam ao ligar. O app grava, e dá para editar à mão (FM em MHz, AM em kHz, memória que falta fica vazia):

  ```json
  {
    "version": 1,
    "last": { "band": "FM1", "frequency": 99.1 },
    "bands": {
      "FM1": { "M1": 104.5, "M2": 99.1, "M7": 87.55 },
      "AM1": { "M1": 1040 }
    }
  }
  ```

  Se o arquivo tiver erro, a tela do rádio avisa e não grava nada até ele ser corrigido. Na primeira vez, as memórias das props antigas (`persist.k4811.*`) são importadas.

## Diferenças do original

Activity com o nome do original (`activity-alias`), para o sistema e outros apps continuarem abrindo este app pelo mesmo componente.

## Build e instalação

Na raiz do projeto:

```bash
./gradlew :radio:assembleRelease
adb install -r radio/build/outputs/apk/release/radio-release.apk
```

Modo demo, sem o serviço de rádio (para testar a tela no emulador):

```bash
adb shell am start -n com.nwd.radio/com.k4811.radio.RadioActivity --ez demo true
```

Status: compila (debug e release). Ainda não testado na central.
