plugins {
    id("com.android.application")
}

android {
    namespace = "app.looplive"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.looplive"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.core:core:1.13.1")
    implementation("com.github.pedroSG94.RootEncoder:library:2.8.1")
}
