# GDM K4811 App suite · Ventoinha

Substituto do app **Fan** da central NWD (`com.nwd.cpumonitor`), com o visual padrão do GDM K4811 App suite.

> Especificação técnica (para quem vai mexer no código, inclusive agentes de IA): [`SPEC.md`](SPEC.md).

| | |
|---|---|
| Package | `com.nwd.cpumonitor` (o mesmo do original) |
| Versão | 2.0.6 (versionCode 20270006, maior que o do original) |
| App original | `radio apk/com.nwd.cpumonitor` |
| Linguagem | **Java puro**, Views clássicas e layouts XML, **sem nenhuma biblioteca** |
| minSdk / targetSdk | **17 / 19**, os mesmos dos originais |
| Tamanho do APK | ~710 KB (release; a maior parte são as 6 fontes) |

## Código

```
fan/src/main/
├── AndroidManifest.xml          FanActivity (singleTask, launcher)
├── java/com/k4811/cpumonitor/
│   ├── FanActivity.java         liga/desliga, automático/manual, temperatura
│   └── ui/FanBladeView.java     hélice animada (segue mcu_smart_fan_switch)
└── res/layout/activity_fan.xml
```

Só grava as chaves `mcu_smart_fan_*` em `Settings.System`. Quem liga o hardware é o sistema/MCU, como no original.

Não está no dock. Abre pelo bloco da ventoinha na tela inicial do launcher. Quando ele está instalado, o `com.nwd.cpumonitor` original some da gaveta.

**Dock e configurações:** usa o mesmo dock do launcher e segue as configurações feitas no [`car-setting`](../car-setting/README.md) (idioma, tema, fonte, ícones do dock). Ver [`../common`](../common/README.md).

## Diferenças do original

Activity com o nome do original (`activity-alias`), para o sistema e outros apps continuarem abrindo este app pelo mesmo componente.

## Build e instalação

```bash
./gradlew :fan:assembleRelease
adb install -r fan/build/outputs/apk/release/fan-release.apk
```

Status: compila (debug e release). Ainda não testado na central.
