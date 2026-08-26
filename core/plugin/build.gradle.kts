plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.hermes.agent.core.plugin"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
    }


    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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
    ksp(libs.hilt.compiler)
    api("javax.inject:javax.inject:1")
    api(libs.kotlinx.coroutines.android)
    api(libs.kotlinx.serialization.json)
    api(libs.timber)
    implementation(libs.androidx.core.ktx)
    implementation(libs.okhttp)
    // Sandboxed JS runtime for script modules. Pure Java, no native code.
    api("org.mozilla:rhino:1.7.14")

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
