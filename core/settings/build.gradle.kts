import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

val localProps = Properties().apply {
    val file = rootProject.file("hermes.local.properties")
    if (file.exists()) file.inputStream().use(::load)
}

android {
    namespace = "com.hermes.agent.core.settings"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
        buildConfigField("String", "CLOUD_API_KEY", "\"${localProps.getProperty("hermes.cloudApiKey") ?: ""}\"")
        buildConfigField("String", "CLOUD_BASE_URL", "\"${project.findProperty("hermes.cloudBaseUrl") ?: "https://api.openai.com/v1"}\"")
        buildConfigField("String", "CLOUD_MODEL", "\"${project.findProperty("hermes.cloudModel") ?: "gpt-4o-mini"}\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        // Carried over from Octo Jotter's build: its annotated constructor properties
        // rely on the pre-2.2 default annotation target.
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }
}

dependencies {
    api(project(":core:util"))
    api(project(":core:domain"))
    implementation(libs.hilt.android)
    api(libs.androidx.datastore.preferences)
    api(libs.okhttp)
    api(libs.kotlinx.coroutines.android)
    api(libs.kotlinx.serialization.json)
    api(libs.timber)
    api("javax.inject:javax.inject:1")

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
}
