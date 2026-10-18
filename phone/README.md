# GDM K4811 App suite · Telefone

Substituto do telefone Bluetooth da central NWD, com o **mesmo package** do original (`com.nwd.android.phone`), com o visual padrão do GDM K4811 App suite.

> Especificação técnica (para quem vai mexer no código, inclusive agentes de IA): [`SPEC.md`](SPEC.md).

| | |
|---|---|
| Package | `com.nwd.android.phone` (o mesmo do original) |
| Versão | 2.0.7 (versionCode 20270007, maior que o do original) |
| App original | `radio apk/com.nwd.android.phone` |

**Dock e configurações:** usa o mesmo dock do launcher e segue as configurações feitas no [`car-setting`](../car-setting/README.md) (idioma, tema, fonte, ícones do dock). Ver [`../common`](../common/README.md).

## Código

| | |
|---|---|
| Linguagem | **Java puro**, Views clássicas e layouts XML, **sem nenhuma biblioteca** |
| minSdk / targetSdk | **17 / 19**, os mesmos do original |
| Tamanho do APK | ~710–740 KB (release; a maior parte são as 6 fontes) |

```
phone/src/main/
├── AndroidManifest.xml          PhoneActivity (singleTask, launcher) e CallReceiver
├── java/com/k4811/android/phone/
│   ├── PhoneActivity.java       abas Discar, Agenda, Histórico e Ajustes, e a tela de chamada
│   └── CallReceiver.java        abre a tela quando entra ou sai uma chamada
└── res/layout/                  activity_phone, phone_row, phone_key
```

O chip fica no serviço de Bluetooth da NWD. O app conversa com ele pelo `BtClient` do [`common`](../common/README.md) e escuta os broadcasts `com.bt.ACTION_BT_*_CALL`, `*_NUMBER`, `*_NAME` e `ACTION_HFP_*`.

## Tela

- **Teclado:** teclado à esquerda; à direita o número, Apagar e Ligar e, embaixo, os contatos cujo número contém os dígitos digitados (sem contar espaços, traços, parênteses e "+", então "98" acha "+55 11 98765-4321"). Toque num contato para ligar.
- **Contatos:** campo de busca no alto, que filtra enquanto digita pelo nome (sem diferenciar maiúsculas nem acentos: "jose" acha "José") ou pelo número. "Buscar" no teclado do Android só fecha o teclado.
- Para testar sem celular: `adb shell am start -n com.nwd.android.phone/com.k4811.android.phone.PhoneActivity --ez demo true` carrega alguns contatos de exemplo.

## Chamadas

- Com a tela do telefone fora da frente, o `CallReceiver` (no manifest) recebe `ACTION_BT_INCOMING_CALL` / `ACTION_BT_OUTGOING_CALL` e mostra a janela flutuante da chamada. Com a tela na frente, entrega o aviso para ela. Número e nome chegam em broadcasts separados e também são repassados para a tela, porque ela pode ainda não estar escutando.
- O Android 10 bloqueia abrir telas em segundo plano. A exceção é ter `SYSTEM_ALERT_WINDOW`, que o app declara e que, com targetSdk < 23, é concedida na instalação. **Precisa ser confirmado na central.**
- Se o `com.nwd.android.phone` original continuar instalado, ele também pode abrir a tela dele numa chamada. Nesse caso as duas telas disputam a frente.
- Ao abrir a tela e ao discar, avisa a central que o telefone é a fonte (`ACTION_APP_IN_OUT` app 10, como o original). Não pausa os outros players: durante a chamada, a central cuida do áudio.

## Diferenças do original

Activity com o nome do original (`activity-alias`), para o sistema e outros apps continuarem abrindo este app pelo mesmo componente.

**Janela flutuante da chamada** (papel do `FloatBar` do original): o `CallFloatService` mostra um cartão de 700×220 a 48 do topo, centralizado, por cima de qualquer app. Tem o nome ou o número, o estado (recebendo, efetuando, em conversa), **Atender** (só recebendo) e **Desligar**, e tocar nele abre o telefone. Ele acompanha os broadcasts de chamada e some quando ela termina e enquanto a tela do telefone está na frente. Usa `TYPE_SYSTEM_ALERT` com `SYSTEM_ALERT_WINDOW`, concedida na instalação. Ainda não testado na central.

**O que o original tinha e este app ainda não tem:** `BtBroadcastReceiver` (serviço que sobe no boot e acompanha a chamada; aqui o `CallReceiver`, declarado no manifest, faz esse papel) e o provider da agenda `com.visualframe.btphone`.

## Build e instalação

```bash
./gradlew :phone:assembleRelease
adb install -r phone/build/outputs/apk/release/phone-release.apk
```

Status: compila (debug e release). Ainda não testado na central.
