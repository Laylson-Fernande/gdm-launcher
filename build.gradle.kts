import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import java.util.Properties

plugins {
    id("com.android.application") version "8.13.2" apply false
    id("com.android.library") version "8.13.2" apply false
}

/*
 * Package de cada app (applicationId), definido só aqui. As classes Java ficam em com.k4811.*.
 *
 * Os apps de função usam o package original da NWD: assinados com a mesma chave dos originais,
 * instalam por cima deles (versionCode maior). Exceções, que ficam ao lado dos originais:
 *   - launcher: com.garagemdomadeira.nwd.launcher (o original é com.android.launcher);
 *   - car-setting: com.nwd.car.setting (o original, com.android.car.setting, usa sharedUserId
 *     com.android.settings, que exige a chave de plataforma).
 *
 * O módulo common recebe essa tabela no BuildConfig (Apps.RADIO etc.), para o dock abrir os apps.
 */
val packages = linkedMapOf(
    "launcher" to "com.garagemdomadeira.nwd.launcher",
    "car-setting" to "com.nwd.car.setting",
    "radio" to "com.nwd.radio",
    "music" to "com.nwd.android.music.ui",
    "bt-music" to "com.nwd.bt.music",
    "phone" to "com.nwd.android.phone",
    "eq" to "com.nwd.audioset",
    "fan" to "com.nwd.cpumonitor",
)

/*
 * Build pessoal (sem a chave dos apps de fábrica): os apps que instalariam por cima dos originais
 * ganham packages próprios e ficam AO LADO deles; o dock e a suíte abrem estes (Apps.* vem desta
 * tabela). Ligado por -Pk4811.packages=personal ou "k4811.packages=personal" no local.properties
 * (fora do git). Ver docs/gerar-apk.md, "Build pessoal".
 */
val personalPackages = linkedMapOf(
    "radio" to "com.k4811.radio",
    "music" to "com.k4811.music",
    "bt-music" to "com.k4811.btmusic",
    "phone" to "com.k4811.phone",
    "eq" to "com.k4811.eq",
    "fan" to "com.k4811.fan",
)

fun packagesMode(): String {
    (findProperty("k4811.packages") as String?)?.let { return it.trim() }
    val local = rootProject.file("local.properties")
    if (!local.isFile) return ""
    val props = Properties().apply { local.inputStream().use { load(it) } }
    return props.getProperty("k4811.packages", "").trim()
}

if (packagesMode() == "personal") {
    packages.putAll(personalPackages)
    logger.lifecycle("k4811.packages=personal: rádio, MP3, BT, telefone, EQ e ventoinha com packages próprios (ao lado dos de fábrica)")
}

/** Nome do campo no BuildConfig do common para cada módulo (PKG_RADIO, PKG_BT_MUSIC…). */
fun fieldOf(module: String) = "PKG_" + module.uppercase().replace('-', '_')

/*
 * Assinatura dos APKs de release.
 *
 * keystore.properties na raiz (fora do git, ver keystore.properties.example):
 *   storeFile=caminho/para/chave.jks
 *   storePassword=...
 *   keyAlias=...
 *   keyPassword=...
 * Sem o arquivo, o release sai assinado com a chave de debug.
 */
val signing = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}

/*
 * Endereço dos dados das vias do mapa integrado (radares, limites de velocidade): o GitHub Pages
 * do repositório, publicado pelo workflow road-data.yml (ver tools/road-data/README.md).
 * Ordem: -ProadDataUrl=...; GITHUB_REPOSITORY (no CI); remote "origin" do git. Vazio = sem
 * endereço (o app só usa o que já estiver baixado ou o "road_data" do nav.config.json).
 */
fun roadDataUrl(): String {
    (findProperty("roadDataUrl") as String?)?.takeIf { it.isNotBlank() }?.let { return it }
    var repo = System.getenv("GITHUB_REPOSITORY").orEmpty()
    if (repo.isEmpty()) {
        val config = rootProject.file(".git/config")
        val text = if (config.isFile) config.readText() else ""
        val origin = Regex("""\[remote "origin"][^\[]*?url\s*=\s*(\S+)""").find(text)?.groupValues?.get(1).orEmpty()
        repo = Regex("""github\.com[:/]([^/]+)/(.+?)(\.git)?$""").find(origin)
            ?.let { it.groupValues[1] + "/" + it.groupValues[2] }.orEmpty()
    }
    val parts = repo.split("/")
    if (parts.size != 2) return ""
    return "https://" + parts[0].lowercase() + ".github.io/" + parts[1] + "/roads/v1/"
}

val roadData = roadDataUrl()

/*
 * Chave da API do Google Maps Platform para o mapa integrado no modo Google (opcional; também pode
 * ser digitada nas Configurações). Só de -Pk4811.googleMapsKey=... ou "k4811.googleMapsKey=..." no
 * local.properties (fora do git): nunca de variável de ambiente, para não entrar nos APKs publicados
 * pelo CI. Vazio = sem chave no build. Ver docs/plano-mapa-google.md.
 */
fun googleMapsKey(): String {
    (findProperty("k4811.googleMapsKey") as String?)?.takeIf { it.isNotBlank() }?.let { return it.trim() }
    val local = rootProject.file("local.properties")
    if (!local.isFile) return ""
    val props = Properties().apply { local.inputStream().use { load(it) } }
    return props.getProperty("k4811.googleMapsKey", "").trim()
}

val googleKey = googleMapsKey()
if (googleKey.isNotEmpty()) logger.lifecycle("k4811.googleMapsKey: chave do Google no build (não publique este APK)")

subprojects {
    plugins.withId("com.android.application") {
        extensions.configure<ApplicationExtension> {
            defaultConfig.applicationId = packages.getValue(project.name)
            val key = if (signing.getProperty("storeFile").isNullOrBlank()) null else signingConfigs.create("key") {
                storeFile = rootProject.file(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
            buildTypes.getByName("release").signingConfig = key ?: signingConfigs.getByName("debug")
        }
        // Beta: o workflow beta.yml injeta a versão via -PbetaVersionName / -PbetaVersionCode.
        // Sobrescreve na API de variantes (depois do android{} de cada app), senão o valor fixo vence.
        // Sem essas props (build normal/release), vale o que está no build.gradle.kts de cada app.
        val betaName = (findProperty("betaVersionName") as String?)?.takeIf { it.isNotBlank() }
        val betaCode = (findProperty("betaVersionCode") as String?)?.toIntOrNull()
        if (betaName != null || betaCode != null) {
            extensions.configure<ApplicationAndroidComponentsExtension> {
                onVariants { variant ->
                    variant.outputs.forEach { output ->
                        if (betaName != null) output.versionName.set(betaName)
                        if (betaCode != null) output.versionCode.set(betaCode)
                    }
                }
            }
        }
    }
    plugins.withId("com.android.library") {
        extensions.configure<LibraryExtension> {
            buildFeatures.buildConfig = true
            for ((module, pkg) in packages) defaultConfig.buildConfigField("String", fieldOf(module), "\"$pkg\"")
            defaultConfig.buildConfigField("String", "ROAD_DATA_URL", "\"$roadData\"")
            defaultConfig.buildConfigField("String", "GOOGLE_MAPS_KEY", "\"$googleKey\"")
        }
    }
}
