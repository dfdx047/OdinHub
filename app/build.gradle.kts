import java.util.Properties

plugins {
    alias(libs.plugins.com.android.application)
    alias(libs.plugins.org.jetbrains.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.com.google.dagger.hilt.android)
    alias(libs.plugins.com.google.devtools.ksp)
    alias(libs.plugins.androidx.room)
}

// Assinatura: o Android só instala uma atualização assinada com a MESMA chave da versão instalada.
// Por omissão os APKs (debug E release) são assinados com a chave de debug DESTE computador
// (~/.android/debug.keystore, criada pelo Android Studio) -- como as releases são compiladas aqui
// (scripts/release.sh) e não no GitHub, a chave é sempre a mesma e o auto-update funciona.
// Opcional: um keystore.properties na raiz (fora do git) usa uma chave própria em vez dessa.
// (Isto fica fora do bloco `android`: lá dentro `java` é a extensão do Gradle e `java.util` não resolve.)
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val releaseStoreFile = keystoreProps.getProperty("storeFile")?.takeIf { it.isNotBlank() }?.let { rootProject.file(it) }?.takeIf { it.exists() }

android {
    namespace = "com.dfdx047.odinhub"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dfdx047.odinhub"
        minSdk = 33
        targetSdk = 35
        versionCode = 19
        versionName = "0.13"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Com chave própria, também no debug (sem ela, o debug já usa a chave de debug).
            if (releaseStoreFile != null) signingConfig = signingConfigs.getByName("release")
        }
        release {
            // initWith copia a assinatura do debug: sem chave própria, a release sai assinada com
            // a chave de debug deste computador (estável entre versões).
            initWith(buildTypes.getByName("debug"))
            if (releaseStoreFile != null) signingConfig = signingConfigs.getByName("release")
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }

    // Necessário pelo Robolectric (testes JVM do AppOverrideRepository com Room in-memory) --
    // sem isto, Robolectric não encontra os recursos/manifesto da app para simular o ambiente
    // Android dentro de um teste unitário comum (testDebugUnitTest), que é o que a CI já corre.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    // Jetpack Compose BOM
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)
    debugImplementation(composeBom)

    // Bundles do TOML
    implementation(libs.bundles.app)
    debugImplementation(libs.bundles.appDebug)
    annotationProcessor(libs.bundles.appAnnotationProcessor)
    ksp(libs.bundles.appKsp)
    testImplementation(libs.bundles.appUnitTest)
    androidTestImplementation(libs.bundles.appAndroidTest)

    // Dependências de Mídia e ExoPlayer (Boot Animation)
    implementation("com.google.android.exoplayer:exoplayer:2.18.0")
    implementation("androidx.media3:media3-exoplayer:1.2.0")
    implementation("androidx.media3:media3-ui:1.2.0")

    // Ferramentas auxiliares
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4")

    // ProcessLifecycleOwner: usado pelo SoundManager para saber quando a UI principal (não o
    // processo inteiro -- o overlay/serviços continuam vivos) vai para segundo plano, e assim
    // parar a música de fundo mesmo com o overlay ainda ativo (ver bug reportado: "a musiquinha
    // deveria parar quando a gente sai do aplicativo, mesmo que o overlay esteja ativado").
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
}