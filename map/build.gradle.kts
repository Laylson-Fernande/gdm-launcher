plugins {
    id("com.android.library")
}

android {
    namespace = "com.k4811.map"
    // compileSdk só define quais APIs o compilador conhece; a compatibilidade vem de minSdk.
    compileSdk = 36

    defaultConfig {
        minSdk = 17     // Android 4.2, igual aos apps originais da NWD
        consumerProguardFiles("consumer-rules.pro")
    }

    lint {
        disable += listOf("ExpiredTargetSdkVersion", "OldTargetApi")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    implementation(project(":common"))
}
