import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.starklauncher.hud"
    minSdk = 24
    targetSdk = 36
    // versionCode dérivé du numéro d'exécution de l'intégration continue.
    //
    // Android refuse d'installer par-dessus une application signée de la même clé si le
    // versionCode n'a pas augmenté : avec une valeur figée à 1, chaque nouvelle APK devait être
    // désinstallée à la main, ce qui efface au passage les réglages et l'autorisation
    // d'accessibilité. Le numéro d'exécution croît strictement, c'est exactement la garantie
    // demandée. En local il vaut 1, pour que le build hors CI reste possible.
    val runNumber = providers.environmentVariable("GITHUB_RUN_NUMBER").orNull?.toIntOrNull() ?: 1
    versionCode = runNumber
    versionName = "1.0.$runNumber"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    // Identité du build, affichée dans l'interface. Sans elle, impossible de savoir quelle
    // version tourne réellement sur un appareil : le nom de l'APK publiée ne change jamais,
    // si bien qu'un cache de navigateur peut resservir une version antérieure sans que rien
    // ne le signale. Lu via providers pour rester compatible avec le cache de configuration.
    val gitSha = providers.environmentVariable("GITHUB_SHA").orNull?.take(7) ?: "local"
    val buildNumber = providers.environmentVariable("GITHUB_RUN_NUMBER").orNull ?: "dev"
    buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
    buildConfigField("String", "BUILD_NUMBER", "\"$buildNumber\"")
  }

  // Les keystores ne sont pas versionnés. Les configurations de signature ne sont donc créées
  // que si le fichier correspondant est réellement présent : sans cela, toute machine dépourvue
  // de keystore — une intégration continue, par exemple — échoue à assembler la variante release.
  // Sans configuration de signature, AGP produit app-release-unsigned.apk.
  signingConfigs {
    val releaseKeystore = file(System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks")
    if (releaseKeystore.exists()) {
      create("release") {
        storeFile = releaseKeystore
        storePassword = System.getenv("STORE_PASSWORD")
        keyAlias = System.getenv("KEY_ALIAS") ?: "upload"
        keyPassword = System.getenv("KEY_PASSWORD")
      }
    }

    val debugKeystore = file("${rootDir}/debug.keystore")
    if (debugKeystore.exists()) {
      create("debugConfig") {
        storeFile = debugKeystore
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
      }
    }
  }

  buildTypes {
    release {
      isCrunchPngs = true

      // Minification désactivée, délibérément.
      //
      // Le moteur de vision fonctionnait en debug et échouait en release sur appareil. Les deux
      // variantes ne diffèrent que par R8 : la cause était donc une classe supprimée ou renommée
      // par la minification. Trois séries de règles de conservation successives n'ont pas suffi à
      // l'identifier — MediaPipe résout une part de ses classes depuis du code natif, où aucune
      // analyse statique ne peut suivre la référence.
      //
      // Le compromis est tranché en faveur du fonctionnement : la minification faisait gagner
      // une vingtaine de mégaoctets et une obfuscation dont cette application n'a aucun besoin,
      // contre un moteur de vision inutilisable. Les règles restent dans proguard-rules.pro pour
      // qui voudrait la réactiver, et la vérification d'empaquetage en intégration continue
      // détectera immédiatement une régression si c'était le cas.
      isMinifyEnabled = false
      isShrinkResources = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

      // Ordre de préférence pour la signature :
      //   1. le keystore de release, s'il est fourni ;
      //   2. la clé de debug, uniquement si -PstarkDebugSigning=true est passé explicitement.
      //      Une release signée en debug est installable pour tester, mais ne doit jamais être
      //      publiée : d'où l'option explicite plutôt qu'un repli silencieux.
      //   3. rien : l'APK sort non signée.
      signingConfig = signingConfigs.findByName("release")
        ?: if (providers.gradleProperty("starkDebugSigning").orNull == "true") {
          signingConfigs.getByName("debug")
        } else {
          null
        }
    }
    debug {
      // À défaut de debug.keystore local, on retombe sur le keystore de debug généré par AGP.
      signingConfig = signingConfigs.findByName("debugConfig") ?: signingConfigs.getByName("debug")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  androidResources {
    // Le modèle .task est une archive : la recompresser dans l'APK empêcherait TensorFlow Lite
    // de le mapper en mémoire directement et rallongerait le démarrage.
    noCompress += "task"
    noCompress += "tflite"
  }
  packaging {
    resources {
      excludes += setOf(
        "META-INF/{AL2.0,LGPL2.1}",
        "META-INF/DEPENDENCIES",
        "META-INF/LICENSE*",
        "META-INF/NOTICE*",
        "**/*.kotlin_metadata"
      )
    }
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
}

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

// Les dépendances inutilisées sont commentées plutôt que supprimées, pour pouvoir être
// réactivées facilement. Room, Retrofit/Moshi/OkHttp et Coil n'étaient référencés nulle part
// dans le code : les laisser actives alourdissait l'APK et le temps de build sans contrepartie.
dependencies {
  // Une dépendance transitive tire androidx.fragment antérieur à 1.3.0, dont les
  // FragmentActivity n'appelaient pas super.onRequestPermissionsResult() et utilisaient des
  // codes de requête invalides — ce qui casse la demande de permission caméra de MainActivity
  // (lint InvalidFragmentVersionForActivityResult, fatal en release).
  //
  // Une contrainte plutôt qu'une dépendance : elle relève le plancher de version si fragment
  // est présent dans le graphe, sans l'y ajouter s'il en est absent.
  constraints {
    implementation("androidx.fragment:fragment:1.3.0") {
      because("ActivityResult exige fragment >= 1.3.0")
    }
  }

  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.camera.camera2)
  implementation(libs.androidx.camera.core)
  implementation(libs.androidx.camera.lifecycle)
  implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  // implementation(libs.androidx.navigation.compose)
  // implementation(libs.androidx.room.ktx)
  // implementation(libs.androidx.room.runtime)
  // implementation(libs.coil.compose)
  // implementation(libs.converter.moshi)
  implementation(libs.firebase.ai)
  implementation(libs.mediapipe.tasks.vision)
  // Uncomment to use Firestore:
  // implementation(libs.firebase.firestore)

  // Uncomment ALL FOUR of the following dependencies together to use Firebase Auth and Google
  // Sign-In via Credential Manager:
  // implementation(libs.firebase.auth)
  // implementation(libs.androidx.credentials)
  // implementation(libs.androidx.credentials.play.services)
  // implementation(libs.googleid)
  implementation(libs.firebase.appcheck.recaptcha)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  // implementation(libs.logging.interceptor)
  // implementation(libs.moshi.kotlin)
  // implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  // implementation(libs.retrofit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  // "ksp"(libs.androidx.room.compiler)
  // "ksp"(libs.moshi.kotlin.codegen)
}
