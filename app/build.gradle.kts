import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// upload key lives outside the repo - keystore.properties (git ignored)
// points at it.  no file = release builds just come out unsigned
val keyProps = Properties ().apply {
    val f = rootProject.file ("keystore.properties")
    if (f.exists ())  f.inputStream ().use { load (it) }
}

android {
    namespace = "app.shaz.tunez"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.shaz.tunez"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create ("release") {
            if (keyProps.containsKey ("storeFile")) {
                storeFile     = file (keyProps ["storeFile"] as String)
                storePassword = keyProps ["storePassword"] as String
                keyAlias      = keyProps ["keyAlias"] as String
                keyPassword   = keyProps ["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            if (keyProps.containsKey ("storeFile"))
                signingConfig = signingConfigs.getByName ("release")
            isMinifyEnabled   = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.media)
    implementation(libs.cast.framework)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
