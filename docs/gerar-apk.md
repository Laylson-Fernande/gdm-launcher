# Gerar o APK

Pré-requisitos e configuração do SDK: ver [Ambiente local e testes](ambiente-local.md). No Windows troque `./gradlew` por `gradlew.bat`.

## Build de release

```bash
./gradlew assembleRelease            # os 8 apps
./gradlew :radio:assembleRelease     # um app só
```

Saída: `<app>/build/outputs/apk/release/<app>-release.apk` (ex.: `radio/build/outputs/apk/release/radio-release.apk`).

O release liga R8 (`isMinifyEnabled` e `isShrinkResources`), sem ofuscação e com `-keep class com.nwd.** / com.bt.**`, porque as interfaces AIDL e os Parcelables dos serviços da NWD não podem ser renomeados.

## Assinatura

A assinatura é configurada no [`build.gradle.kts`](../build.gradle.kts) da raiz, a partir de `keystore.properties` (fora do git):

1. Copie o modelo: `cp keystore.properties.example keystore.properties`.
2. Coloque a chave em `keys/` (também fora do git) e preencha:
   ```properties
   storeFile=keys/nwd.jks
   storePassword=...
   keyAlias=...
   keyPassword=...
   ```
   O caminho de `storeFile` pode ser relativo à raiz do projeto.

**Sem `keystore.properties`, o release sai assinado com a chave de debug.** Esse APK serve para o launcher e o car-setting (que ficam ao lado dos originais) e para o emulador, mas **não** instala por cima dos apps de fábrica da central (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`). Para substituir rádio, música, BT, telefone, EQ e ventoinha é preciso a mesma chave dos originais.

Nunca suba APK, chave ou senha: o `.gitignore` já cobre `*.apk`, `*.jks`, `keys/`, `keystore.properties` e `local.properties`.

## Build pessoal (sem a chave dos apps de fábrica)

Rádio, MP3, música Bluetooth, telefone, equalizador e ventoinha usam os packages dos apps de fábrica da NWD (`com.nwd.radio`…) para instalar **por cima** deles, o que exige a chave da suíte. Com outra chave (uma chave pessoal, por exemplo), a central recusa (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`), e não dá para desinstalar os originais.

Para usar a sua versão desses apps, gere com packages próprios, que ficam **ao lado** dos de fábrica:

```properties
# local.properties (fora do git)
k4811.packages=personal
```

ou `./gradlew assembleRelease -Pk4811.packages=personal`. O build avisa: `k4811.packages=personal: …`.

| App | Package de fábrica | Package pessoal |
|---|---|---|
| radio | `com.nwd.radio` | `com.k4811.radio` |
| music | `com.nwd.android.music.ui` | `com.k4811.music` |
| bt-music | `com.nwd.bt.music` | `com.k4811.btmusic` |
| phone | `com.nwd.android.phone` | `com.k4811.phone` |
| eq | `com.nwd.audioset` | `com.k4811.eq` |
| fan | `com.nwd.cpumonitor` | `com.k4811.fan` |

O launcher e o car-setting não mudam. O dock e a suíte inteira passam a abrir os packages pessoais (`Apps.*` vem da tabela do `build.gradle.kts`). Instale os **8** APKs do mesmo build, porque o launcher precisa saber os packages novos.

Os de fábrica continuam instalados e ativos:
- Botões do volante, a tecla de modo e outros apps da NWD abrem os apps de fábrica, não os da suíte.
- Eventos do sistema chegam aos dois:
  - **Chamadas Bluetooth:** a tela de chamada de fábrica e a da suíte podem aparecer juntas.
  - **Religar e mudança de estado da MCU:** o equalizador de fábrica e o da suíte reaplicam o DSP.
  - **Retomar a música ao ligar:** `com.nwd.ACTION_BACKGROUND_MEDIA_PLAY` chega aos players de fábrica e aos da suíte.
- A gaveta de apps mostra os dois.

Para esconder um app de fábrica sem root: `adb shell pm disable-user --user 0 <package>`. Para desfazer: `adb shell pm enable <package>`. Teste um de cada vez: se a central abrir o app de fábrica por um componente fixo (tecla de modo, volante), esse atalho deixa de funcionar com ele desligado. O `com.nwd.audioset` pode ser o serviço de DSP que o sistema usa; evite desligá-lo.

## Chave do Google (mapa integrado no modo Google)

Opcional. A chave da API do Google Maps Platform pode ser digitada na central (Configurações › Customizar navegação › Chave da API do Google) ou entrar no build:

```properties
# local.properties (fora do git)
k4811.googleMapsKey=AIza...
```

ou `./gradlew assembleRelease -Pk4811.googleMapsKey=AIza...`. O build avisa: `k4811.googleMapsKey: chave do Google no build (não publique este APK)`. Ela **não** vem de variável de ambiente, de propósito: os workflows de release e beta nunca põem a chave nos APKs publicados. Como criar e restringir a chave: [`plano-mapa-google.md`](plano-mapa-google.md#chave-da-api).

## Versão

Cada app define a sua em `<app>/build.gradle.kts`:

```kotlin
versionCode = 20270007  // maior que o de todos os originais
versionName = "2.0.7"
```

- Todos os apps andam juntos na mesma versão.
- O `versionCode` precisa ficar acima do dos apps de fábrica (o maior, `com.nwd.audioset`, está em 20260915), senão a instalação por cima falha.
- O CI lê a versão do `launcher/build.gradle.kts`.
- Ao subir a versão, crie `releases/<versionName>.md` com as notas: o CI de release falha sem ele.

## Conferir o APK

Necessário quando mexer em manifest ou package. As ferramentas ficam em `<sdk>/build-tools/<versão>/` (no Windows, `aapt.exe` e `apksigner.bat`).

```bash
BT=$(ls -d ~/Library/Android/sdk/build-tools/*/ | tail -1)   # ajuste para o seu SDK
${BT}aapt dump badging radio/build/outputs/apk/release/radio-release.apk | head -3     # package, versão, sdk
${BT}aapt dump xmltree radio/build/outputs/apk/release/radio-release.apk AndroidManifest.xml
${BT}apksigner verify --print-certs radio/build/outputs/apk/release/radio-release.apk  # qual chave assinou
```

Confira que o package é o da tabela `packages`, que `targetSdkVersion` continua 19 e que o certificado é o esperado.

## Levar para a central

Dois scripts copiam todos os APKs de release (precisam de `sh`: macOS, Linux, Git Bash ou WSL):

```bash
./copy-to-updateApk.sh --build   # gera e copia para updateApk/ na raiz do projeto
./copy-apks.sh --build           # gera e copia para o pen drive
```

Sem `--build` eles só copiam o que já foi gerado.

O `copy-apks.sh` copia para a pasta `UpdateApk` do pen drive. O destino padrão é um caminho de macOS; em outro sistema informe o destino em `APKS_DIR` (a pasta-mãe precisa existir, ou seja, o pen drive precisa estar montado):

```bash
APKS_DIR=/media/<usuario>/PENDRIVE/UpdateApk ./copy-apks.sh --build   # Linux
APKS_DIR=/e/UpdateApk ./copy-apks.sh --build                          # Git Bash, pen drive no E:
```

Na central, instale os APKs a partir da pasta `UpdateApk` do pen drive ou cartão. Depois de instalar o launcher, aperte Início, escolha o launcher novo e marque **Sempre**.

## CI (GitHub Actions)

Os dois workflows usam JDK 17, montam o `keystore.properties` a partir dos secrets `NWD_KEYSTORE_BASE64`, `NWD_KEYSTORE_PASSWORD`, `NWD_KEY_ALIAS` e `NWD_KEY_PASSWORD`, rodam `./gradlew assembleRelease` e exigem os 8 APKs.

| Workflow | Quando roda | O que publica |
|---|---|---|
| [`release.yml`](../.github/workflows/release.yml) | push na `main` (só se a versão do launcher mudou e a tag ainda não existe) ou manual com `force` | release `v<versão>` com os APKs, `updateApk.zip` e as notas de `releases/<versão>.md` |
| [`beta.yml`](../.github/workflows/beta.yml) | push na branch `beta` ou manual | pre-release `v<alvo>-beta.<N>` com os APKs e `updateApk-<beta>.zip` |

No beta a versão é carimbada no build por `-PbetaVersionName` / `-PbetaVersionCode` (tratados no `build.gradle.kts` da raiz). O `versionCode` do beta fica acima do estável atual e abaixo do alvo, para que o estável, quando sair, instale por cima dos betas. O mesmo mecanismo funciona localmente:

```bash
./gradlew assembleRelease -PbetaVersionName=2.1.0-beta.1 -PbetaVersionCode=20270008
```

Para gerar um release oficial: suba `versionCode`/`versionName` em todos os `<app>/build.gradle.kts`, crie `releases/<versão>.md` e faça o push na `main`.
