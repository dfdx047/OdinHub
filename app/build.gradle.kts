plugins {
    alias(libs.plugins.com.android.application)
    alias(libs.plugins.org.jetbrains.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.com.google.dagger.hilt.android)
    alias(libs.plugins.com.google.devtools.ksp)
    alias(libs.plugins.androidx.room)
}

android {
    namespace = "com.dfdx047.odinhub"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dfdx047.odinhub"
        minSdk = 33
        targetSdk = 35
        versionCode = 14
        versionName = "0.8"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            initWith(buildTypes.getByName("debug"))
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