plugins {
    id("com.gradleup.shadow") version "8.3.5"
    id("qupath-conventions")
}

qupathExtension {
    name = "FlowPath - GatingTree"
    group = "io.github.qupath"
    version = "1.10.0"
    description = "Interactive tree-based cell phenotyping with hierarchical gates. Designed for multiplexed imaging data."
    automaticModule = "qupath.ext.flowpath"
}

dependencies {
    shadow(libs.bundles.qupath)
    shadow(libs.bundles.logging)
    shadow(libs.qupath.fxtras)
    testImplementation(libs.bundles.qupath)
    testImplementation(libs.junit)
    testImplementation("org.openjfx:javafx-base:25.0.2")
    testImplementation("org.openjfx:javafx-graphics:25.0.2")
    testImplementation("org.openjfx:javafx-controls:25.0.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
