plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.hermes.agent.core.tools"
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
    api(project(":core:settings"))
    api(project(":core:persistence"))
    api(project(":core:memory"))
    api(project(":core:llm"))
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    api(libs.okhttp)
    // Unused inside this module's own source — kept as `api` (not `implementation`)
    // deliberately, so both apps' data/server/HermesApiServer.kt-equivalent get
    // NanoHTTPD transitively from depending on :core:tools. Narrowing this to
    // `implementation` would silently drop it from the app classpath.
    api(libs.nanohttpd)
    api(libs.jsch)
    api(libs.kotlinx.coroutines.android)
    api(libs.kotlinx.serialization.json)
    api(libs.timber)
    api("javax.inject:javax.inject:1")

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}

