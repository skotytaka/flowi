plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.budget_planner"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.budget_planner"
        minSdk = 24
        targetSdk = 35
        versionCode = 8
        versionName = "0.9.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    buildFeatures {
        compose = true
    }
}

// Deletes accidental duplicate .png copies of icon files before AAPT2 runs.
// AAPT2 treats ic_launcher.png and ic_launcher.webp in the same folder as
// duplicate resources and refuses to build — this cleans them up automatically.
tasks.register("cleanDuplicateIcons") {
    doFirst {
        val densities = listOf("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi")
        val names = listOf("ic_launcher.png", "ic_launcher_round.png")
        densities.forEach { density ->
            names.forEach { name ->
                val f = file("src/main/res/mipmap-$density/$name")
                if (f.exists()) {
                    f.delete()
                    println("Deleted duplicate: $f")
                }
            }
        }
    }
}
tasks.named("preBuild").configure { dependsOn("cleanDuplicateIcons") }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}