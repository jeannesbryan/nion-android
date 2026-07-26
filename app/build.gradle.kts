plugins {
    id("com.android.application")
}

val geckoviewArtifact: String by project
val geckoviewVersion: String by project

android {
    namespace = "io.github.jeannesbryan.nion"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.jeannesbryan.nion"

        minSdk = 26
        targetSdk = 36

        versionCode = 5
        versionName = "0.5.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(
        "org.mozilla.geckoview:$geckoviewArtifact:$geckoviewVersion"
    )

    implementation("info.guardianproject:tor-android:0.4.9.11")
    implementation("info.guardianproject:jtorctl:0.4.5.7")
    implementation("androidx.activity:activity:1.13.0")
}
