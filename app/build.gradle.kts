plugins {
    id("com.android.application")
}

val geckoviewArtifact: String by project
val geckoviewVersion: String by project


val nionReleaseStoreFile =
    providers.environmentVariable(
        "NION_RELEASE_STORE_FILE"
    )

val nionReleaseStorePassword =
    providers.environmentVariable(
        "NION_RELEASE_STORE_PASSWORD"
    )

val nionReleaseKeyAlias =
    providers.environmentVariable(
        "NION_RELEASE_KEY_ALIAS"
    )

val nionReleaseKeyPassword =
    providers.environmentVariable(
        "NION_RELEASE_KEY_PASSWORD"
    )

android {
    namespace = "io.github.jeannesbryan.nion"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.jeannesbryan.nion"

        minSdk = 26
        targetSdk = 36

        versionCode = 10
        versionName = "1.0.0"
    }

    signingConfigs {
        create("release") {
            if (nionReleaseStoreFile.isPresent) {
                storeFile =
                    file(
                        nionReleaseStoreFile.get()
                    )
            }

            if (
                nionReleaseStorePassword
                    .isPresent
            ) {
                storePassword =
                    nionReleaseStorePassword
                        .get()
            }

            if (nionReleaseKeyAlias.isPresent) {
                keyAlias =
                    nionReleaseKeyAlias.get()
            }

            if (
                nionReleaseKeyPassword
                    .isPresent
            ) {
                keyPassword =
                    nionReleaseKeyPassword
                        .get()
            }
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig =
                signingConfigs.getByName(
                    "release"
                )

            isMinifyEnabled = false
            isShrinkResources = false
        }
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
    implementation("androidx.core:core:1.19.0")
}
