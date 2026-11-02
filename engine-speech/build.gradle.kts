plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":core-domain"))
    implementation(project(":core-platform-api"))

    implementation(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)}

kotlin {
    jvmToolchain(17)
}
