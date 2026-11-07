plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Numéros de version fournis par GitHub Actions (numéro d'exécution) ; valeurs par défaut en local.
val appVersionCode = (project.findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 1
val appVersionName = (project.findProperty("appVersionName") as String?) ?: "1.0.0"
val releaseKeystore = rootProject.file("release.keystore")
val keystorePassword: String? = System.getenv("KEYSTORE_PASSWORD")
// Alias par défaut "cadran" : permet de réutiliser tel quel le keystore de Cadran.
val keystoreAlias: String = System.getenv("KEYSTORE_ALIAS")?.takeIf { it.isNotBlank() } ?: "cadran"

android {
    namespace = "fr.micolomike.mestransports"
    compileSdk = 35

    defaultConfig {
        applicationId = "fr.micolomike.mestransports"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        create("release") {
            if (releaseKeystore.exists() && keystorePassword != null) {
                storeFile = releaseKeystore
                storePassword = keystorePassword
                keyAlias = keystoreAlias
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (releaseKeystore.exists() && keystorePassword != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
}
