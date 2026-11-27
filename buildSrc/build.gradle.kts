plugins {
    `kotlin-dsl`
}

repositories {
    google()
    mavenCentral()
}

dependencies {
    // ASM stays on the build classpath. The app does not depend on it.
    implementation("org.ow2.asm:asm:9.8")
}
