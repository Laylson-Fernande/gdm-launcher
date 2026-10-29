# adb na central: conectar e desativar apps de fábrica

No build pessoal (sem a chave dos apps de fábrica, ver [Gerar o APK](gerar-apk.md#build-pessoal-sem-a-chave-dos-apps-de-fábrica)), os apps da suíte ficam **ao lado** dos originais da NWD. Os originais continuam instalados e às vezes atrapalham. O caso mais comum é o player de música Bluetooth de fábrica abrindo no lugar do launcher quando o carro liga com a música BT tocando.

O launcher já se defende disso: a opção **Suíte na frente ao ligar**, em Configurações › Sistema, reabre a última tela da suíte nos primeiros segundos. A solução definitiva é desativar esses apps com o adb. Este guia mostra como.

> Desativar não apaga nada e pode ser desfeito a qualquer momento (ver [Desfazer](#desfazer)).

## 1. Verificar se a central tem adb

O caminho mais provável é o **adb sem fio (Wi-Fi)**. As portas USB da central normalmente só servem para pen drive, então o adb pelo cabo costuma não funcionar.

1. **Abra as Configurações do Android.** Na tela inicial do launcher, **segure numa área vazia** (é a saída de emergência). Ou use o atalho "Config. Veículo" nas Configurações da suíte, se ele levar ao Android.
2. **Ative o modo desenvolvedor.** Em **Sobre o dispositivo** (ou *Sobre o tablet*), toque **7 vezes** em **Número da versão**. Aparece o aviso "Você agora é um desenvolvedor".
3. **Procure a depuração por Wi-Fi.** Em **Sistema › Opções do desenvolvedor**, procure **Depuração por Wi-Fi**. Ela existe a partir do Android 11; a central de testes é Android 15.

Se as opções de desenvolvedor não aparecerem, a NWD escondeu esse menu nesta ROM e não dá para usar o adb pelo caminho normal. Nesse caso, a opção "Suíte na frente ao ligar" continua resolvendo o problema.

## 2. Conectar pelo Wi-Fi

O PC precisa do adb: é o `platform-tools` do Android SDK. No ambiente deste projeto ele fica em `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe` (ver [Ambiente local e testes](ambiente-local.md)). Sem o SDK, baixe só o [SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools).

1. Coloque a **central e o PC na mesma rede Wi-Fi**. Pode ser o roteador de casa com o carro estacionado perto de casa, ou o **hotspot do celular** com a central e o PC conectados nele.
2. Na central: **Opções do desenvolvedor › Depuração por Wi-Fi**. Ligue a opção e toque em **Parear dispositivo com código de pareamento**. A central mostra um **IP:porta de pareamento** e um **código de 6 dígitos**.
3. No PC, no PowerShell:

   ```powershell
   cd $env:LOCALAPPDATA\Android\Sdk\platform-tools

   # Pareamento (só na primeira vez): IP e porta da janela de pareamento; pede o código de 6 dígitos
   .\adb pair 192.168.x.x:PORTA_DE_PAREAMENTO

   # Conexão: IP e porta da tela "Depuração por Wi-Fi" (é OUTRA porta, não a do pareamento)
   .\adb connect 192.168.x.x:PORTA

   # Conferir: a central deve aparecer como "device"
   .\adb devices
   ```

Observações:
- A porta muda cada vez que a depuração por Wi-Fi é ligada de novo. Depois de reiniciar a central, repita só o `adb connect` com a porta nova; o pareamento fica guardado.
- Se outro aparelho também estiver conectado ao adb (um celular, por exemplo), indique a central em cada comando com `-s`: `.\adb -s 192.168.x.x:PORTA shell ...`.

## 3. Identificar os pacotes

**Pacotes da NWD instalados:**

```powershell
.\adb shell pm list packages | findstr nwd
```

**Os que interessam aqui** são os players que a suíte substitui. No build pessoal eles ficam instalados ao lado dos apps `com.k4811.*`:

| App de fábrica | Pacote | Substituído por (build pessoal) |
|---|---|---|
| Música Bluetooth | `com.nwd.bt.music` | `com.k4811.btmusic` |
| Música (MP3/USB) | `com.nwd.android.music.ui` | `com.k4811.music` |

**Qual app abriu sozinho:** para confirmar, ligue o carro (ou espere o app de fábrica aparecer) e rode:

```powershell
.\adb shell dumpsys activity activities | findstr topResumedActivity
```

A linha mostra o pacote e a tela que estão na frente (por exemplo, `com.nwd.bt.music/...`).

**Antes de desativar, confira quem tem o serviço de Bluetooth.** A suíte inteira usa o serviço de Bluetooth da NWD, e ele mora em algum pacote de fábrica:

```powershell
.\adb shell cmd package query-services -a com.bt.ACTION_START_SERVICE
```

O resultado mostra o pacote do serviço. **Nunca desative esse pacote**, senão o Bluetooth para de funcionar: telefone, música BT e o card da tela inicial. Pelo mesmo motivo, não desative o app de telefone de fábrica (`com.nwd.android.phone`), porque o serviço pode estar nele.

## 4. Desativar

```powershell
.\adb shell pm disable-user --user 0 com.nwd.bt.music
.\adb shell pm disable-user --user 0 com.nwd.android.music.ui
```

Cada comando responde algo como `Package com.nwd.bt.music new state: disabled-user`. Para conferir:

```powershell
.\adb shell pm list packages -d | findstr nwd   # -d = só os desativados
```

Depois disso, ligue o carro com a música Bluetooth tocando: deve aparecer a tela da suíte, não a de fábrica. Se algo parar de funcionar (Bluetooth, som), [desfaça](#desfazer) e avise com os logs (Configurações › Sistema › Gravar log; arquivos em `K4811/logs/`).

## Desfazer

```powershell
.\adb shell pm enable com.nwd.bt.music
.\adb shell pm enable com.nwd.android.music.ui
```

## Problemas comuns

| Sintoma | O que fazer |
|---|---|
| `adb pair` falha ou pede código de novo | O código e a porta de pareamento valem só enquanto a janela de pareamento está aberta na central. Abra de novo e use os valores novos |
| `adb connect` dá "failed to connect" | A porta da tela "Depuração por Wi-Fi" mudou (ela muda ao religar), ou PC e central estão em redes diferentes |
| `adb devices` mostra `unauthorized` | Aceite o aviso "Permitir depuração" que aparece na tela da central |
| `pm disable-user` responde "Cannot disable a protected package" ou erro de permissão | A ROM protege esse pacote. Não tem como desativar; fica valendo a opção "Suíte na frente ao ligar" |
| A depuração por Wi-Fi desliga sozinha | Ela desliga quando o Wi-Fi cai (por exemplo, ao desligar o carro). Ligue de novo e repita o `adb connect` |
