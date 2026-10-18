# SPEC · fan

Leia antes o [`AGENTS.md`](../AGENTS.md) e o [`common/SPEC.md`](../common/SPEC.md).

| | |
|---|---|
| applicationId | `com.nwd.cpumonitor` (por cima do original) |
| Pacote Java | `com.k4811.cpumonitor` |
| Application | `com.k4811.common.SuiteApp` |
| Activity | `FanActivity` (singleTask, `sensorLandscape`) + alias `com.nwd.cpumonitor.MainActivity` |

## Responsabilidade

Preferências da ventoinha de refrigeração. **Só grava `Settings.System`**; quem liga o hardware é o sistema/MCU.

| Chave (`Settings.System`) | Significado |
|---|---|
| `fan_auto_state` | automático |
| `fan_manual_state` | ligada no manual |
| `fan_execute_temp` | temperatura de acionamento |
| `fan_with_amp` | ligar junto com o amplificador |
| `mcu_smart_fan_switch` | estado real (lido; a hélice `ui/FanBladeView` gira com ele) |

Não está no dock: abre pelo módulo Ventoinha da tela inicial (`Apps.FAN`). Precisa de `WRITE_SETTINGS` (concedida na instalação com target 19).
