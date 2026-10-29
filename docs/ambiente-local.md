# Ambiente local e testes

## Pré-requisitos

| Ferramenta | Versão | Observação |
|---|---|---|
| JDK | 17 | é o que o CI usa (Temurin 17); o AGP 8.13 exige no mínimo 17 |
| Android SDK | Platform 36 + Build-Tools recentes + Platform-Tools | o jeito mais simples é instalar o Android Studio |
| Gradle | — | não precisa instalar: use o wrapper (`gradlew` / `gradlew.bat`, baixa o Gradle 8.14.3) |
| adb | — | vem no Platform-Tools; para instalar e testar no emulador ou na central |
| Git Bash ou WSL (Windows) | — | só para rodar os scripts `.sh` |

Não há nenhuma dependência de biblioteca: o primeiro build baixa só o Gradle e o Android Gradle Plugin.

## Configurar o SDK

Crie `local.properties` na raiz (fica fora do git) apontando para o SDK:

```properties
# macOS
sdk.dir=/Users/<usuario>/Library/Android/sdk
# Linux
sdk.dir=/home/<usuario>/Android/Sdk
# Windows (barras e dois-pontos escapados)
sdk.dir=C\:\\Users\\<usuario>\\AppData\\Local\\Android\\Sdk
```

Alternativa: definir a variável de ambiente `ANDROID_HOME`. Abrir o projeto no Android Studio cria o `local.properties` sozinho.

Se o Gradle reclamar da plataforma, instale-a pelo SDK Manager do Android Studio ou por linha de comando, com as [command-line tools](https://developer.android.com/studio#command-line-tools-only) extraídas em `<sdk>/cmdline-tools/latest/`:

```bash
# versões novas das command-line tools (o sdkmanager virou a CLI "android")
android --sdk=<sdk> sdk install platform-tools "platforms/android-36" "build-tools/35.0.0"
# versões antigas
sdkmanager "platform-tools" "platforms;android-36" "build-tools;35.0.0"
```

## Compilar

No Windows troque `./gradlew` por `gradlew.bat` (ou `.\gradlew` no PowerShell).

```bash
./gradlew :radio:compileReleaseJavaWithJavac   # só compila um app (rápido, para checar erros)
./gradlew assembleDebug                        # APKs de debug de todos os apps
./gradlew :launcher:assembleDebug              # APK de debug de um app
./gradlew assembleRelease                      # APKs de release (ver gerar-apk.md)
```

APKs de debug ficam em `<app>/build/outputs/apk/debug/<app>-debug.apk`.

**Não há testes automatizados.** Para este projeto, "pronto" significa:

1. `./gradlew assembleRelease` sem erro;
2. se mexeu em manifest ou package, conferir o APK gerado (ver [Gerar o APK](gerar-apk.md#conferir-o-apk));
3. nenhum nome proibido no código (ver regra 6 do [`AGENTS.md`](../AGENTS.md)):
   ```bash
   grep -rIil "madeira\|garagem\|gdm" --exclude-dir=build --exclude-dir=.git .
   ```
   Só pode aparecer o package do launcher (`build.gradle.kts` e documentação dele) e o próprio comando de busca (no `AGENTS.md` e aqui).
4. testar na central quando possível.

## Testar no emulador

O emulador serve para ver **layout, tema, idioma e navegação**. Os serviços da NWD (rádio, Bluetooth, SettingService, MCU) não existem nele, então rádio, BT, telefone, EQ, volume e ventoinha ficam sem dados ou não respondem.

### Pré-requisito: aceleração de hardware

O emulador precisa da virtualização do processador:

1. Na BIOS, ligue **SVM Mode** (AMD) ou **Intel VT-x** (Intel).
2. No Windows, ative **Plataforma do Hipervisor do Windows** e **Plataforma de Máquina Virtual** (`optionalfeatures`, ou em um PowerShell de administrador) e reinicie:
   ```powershell
   Enable-WindowsOptionalFeature -Online -FeatureName HypervisorPlatform -All -NoRestart
   Enable-WindowsOptionalFeature -Online -FeatureName VirtualMachinePlatform -All -NoRestart
   ```
3. Confira: `emulator -accel-check` deve dizer `WHPX ... is installed and usable`.

### Criar o AVD

A central de testes atual é uma K2401 com **Android 15**: use a imagem do Android 15 sem Google (a mais parecida com a ROM da NWD). A do Android 10 continua servindo para centrais mais antigas.

1. Instale o emulador e a imagem:
   ```bash
   android --sdk=<sdk> sdk install emulator "system-images/android-35/default/x86_64"   # Android 15
   android --sdk=<sdk> sdk install "system-images/android-29/default/x86_64"            # Android 10 (opcional)
   ```
2. Crie o AVD (pelo Device Manager do Android Studio ou por linha de comando):
   ```bash
   echo no | avdmanager create avd -n K4811_Android15 -k "system-images;android-35;default;x86_64" -d pixel
   ```
3. Ajuste a tela em `~/.android/avd/K4811_Android15.avd/config.ini`:
   ```ini
   hw.lcd.width=1280
   hw.lcd.height=720
   hw.initialOrientation=landscape
   hw.cpu.ncore=4
   skin.path=_no_skin
   showDeviceFrame=no
   hw.lcd.density=160
   hw.keyboard=yes
   hw.ramSize=2048
   skin.name=1280x720
   ```
   Como `Ui.applyDesignDensity` ajusta a escala, a densidade não importa muito.
4. Suba o emulador:
   ```bash
   emulator -avd K4811_Android15 -no-snapshot-save -no-boot-anim -timezone America/Sao_Paulo
   ```
   Sem `-timezone` o app de setup do emulador (`com.android.sdksetup`) pode travar com "Timezone Unknown/Unknown". O travamento é do emulador, não da suíte.

### Instalar e abrir

1. Gere e instale os apps com `-g`:
   ```bash
   ./gradlew assembleDebug
   for a in launcher car-setting radio music bt-music phone eq fan; do
     adb install -r -g --bypass-low-target-sdk-block $a/build/outputs/apk/debug/$a-debug.apk
   done
   adb shell cmd package set-home-activity com.garagemdomadeira.nwd.launcher/com.k4811.launcher.HomeActivity
   ```
   - No emulador não existem os apps de fábrica, então os apps "por cima do original" instalam normalmente com a chave de debug.
   - `--bypass-low-target-sdk-block` (Android 14+): sem ele o Android recusa o targetSdk 19 com `INSTALL_FAILED_DEPRECATED_SDK_VERSION`. A ROM da NWD libera isso sozinha; no Android 10 a opção não existe (tire-a).
   - O `-g` concede as permissões na instalação. Sem ele, o Android 10 do emulador mostra uma tela de revisão de permissões ("Choose what to allow…") por ser um app com targetSdk < 23.
   - O aviso "This app was built for an older version of Android" aparece uma vez por app, também por causa do targetSdk 19. Basta tocar em OK. Na central nenhum dos dois aparece.
   - No Android 15 em 1280×720 / 160 dpi o emulador se comporta como tablet: mostra uma barra de apps embaixo e o aviso "Viewing full screen". São do emulador; a central não tem.
   - O launcher pergunta se deve virar o "launcher padrão" da NWD (`persist.nwd.launcher.default`, que o emulador não tem). Toque em "Agora não".
2. Abra o launcher com a tecla Início ou direto:
   ```bash
   adb shell am start -n com.garagemdomadeira.nwd.launcher/com.k4811.launcher.HomeActivity
   ```

### Modo demo do rádio

O rádio tem dados de demonstração para ver a tela sem a central:

```bash
./gradlew :radio:assembleDebug
adb install -r -g radio/build/outputs/apk/debug/radio-debug.apk
adb shell am start -n com.nwd.radio/com.k4811.radio.RadioActivity --ez demo true
```

### Configuração compartilhada no emulador

Sem o car-setting instalado, os apps usam os padrões. Para testar outro tema, idioma ou layout, instale o car-setting e mude por ele, ou edite o JSON direto:

```bash
adb shell cat /sdcard/nwd-configs/launcher.json
adb push launcher.json /sdcard/nwd-configs/launcher.json
```

As chaves estão em [`common/SPEC.md`](../common/SPEC.md#launcherjson-só-o-car-setting-grava).

### Simular o GPS

O mapa integrado e os módulos de velocidade precisam de posições. No emulador:

```bash
# uma posição (longitude primeiro), altitude, satélites e velocidade
adb emu geo fix -46.6333 -23.5505 760 8 14
```

Para simular o carro andando, mande um `geo fix` por segundo com pontos de uma rota (ex.: a geometria GeoJSON de `https://routing.openstreetmap.de/routed-car/route/v1/driving/<lon>,<lat>;<lon>,<lat>?overview=full&geometries=geojson`, interpolada a cada N metros). Cuidados, todos do emulador:

- A velocidade do `geo fix` é lida em **nós** no Android 15 (como diz a ajuda) e em **m/s** no Android 10.
- O `geo fix` informa rumo 0° fixo. Durante a navegação a seta usa a direção da rota, então não faz diferença; fora dela a seta aponta para o norte.
- O emulador repete a última posição a cada segundo. Não misture `geo fix` e `geo nmea` na mesma simulação: ele alterna entre as duas fontes e o carro "pula". As sentenças NMEA também não juntam posição (`$GPGGA`) e velocidade/rumo (`$GPRMC`) num único fix.
- Rode uma simulação por vez; se sobrar uma antiga rodando, as posições se misturam.

### Emulador com 2 GB

Para medir desempenho perto de uma central de entrada, use um AVD com `hw.ramSize=2048` e `hw.cpu.ncore=4` e APKs de **release**. Método, limites e resultados em [Desempenho](desempenho.md).

### Modo desenvolvedor

Mostra diagnósticos, o último erro e o menu Avançado:

```bash
adb shell touch /sdcard/LAUNCHER_DEV_MODE.txt   # liga
adb shell rm /sdcard/LAUNCHER_DEV_MODE.txt      # desliga
```

### Logs

R8 roda sem ofuscação e mantém número de linha, então as pilhas de erro no logcat são legíveis mesmo no release:

```bash
adb logcat | grep -i k4811
```

**Na central, sem adb:** ligue Configurações → Sistema → **Gravar log para diagnóstico**. Cada app da suíte passa a gravar o próprio log, inclusive erros e travamentos, em `/sdcard/K4811/logs/<app>.log` (`launcher.log`, `car-setting.log`, `radio.log`…). Cada arquivo vai até 2 MB, com um anterior (`<app>.1.log`). Reproduza o problema, copie a pasta `K4811/logs` para um pen drive com um gerenciador de arquivos (ou `adb pull /sdcard/K4811/logs`) e desligue a opção depois. Um app comum só lê os próprios logs, então os arquivos não trazem o log do sistema inteiro.

## Testar na central

É o único teste que vale para rádio, Bluetooth, telefone, EQ, volume e "uma fonte por vez".

- **Com adb** (Wi-Fi ou USB, se a central permitir): `adb install -r <apk>`.
- **Sem adb**: copie os APKs para a pasta `UpdateApk` de um pen drive ou cartão e instale pela central (ver [Gerar o APK](gerar-apk.md#levar-para-a-central)).

Para os apps que substituem os de fábrica, o APK precisa estar **assinado com a chave da NWD** (APK de release com `keystore.properties`); com a chave de debug a instalação falha com `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Launcher e car-setting instalam com qualquer chave.

Depois de instalar o launcher, defina-o como tela inicial:

```bash
adb shell cmd package set-home-activity com.garagemdomadeira.nwd.launcher/com.k4811.launcher.HomeActivity
```

Sem adb: aperte Início, escolha o launcher novo e marque **Sempre**.

**Voltar ao launcher original:** `adb uninstall com.garagemdomadeira.nwd.launcher`. **Saída de emergência:** segurar a área vazia da tela inicial abre as Configurações do Android.

Ao relatar uma mudança, diga sempre se ela foi ou não testada na central.
