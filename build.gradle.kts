plugins {
    id("com.gradleup.shadow") version "8.3.5"
    id("qupath-conventions")
}

qupathExtension {
    name = "FlowPath"
    group = "io.github.qupath"
    version = "2.0.1"
    description = "FlowJo-style cell phenotyping for multiplexed imaging: hierarchical gating plus UMAP visualisation of the resulting phenotypes."
    automaticModule = "qupath.ext.flowpath"
}

dependencies {
    shadow(libs.bundles.qupath)
    shadow(libs.bundles.logging)
    shadow(libs.qupath.fxtras)

    // SMILE provides the UMAP implementation (smile.manifold.UMAP +
    // smile.graph.NearestNeighborGraph). Bundled into the fat JAR, minus three sets
    // of dependencies FlowPath never reaches:
    //
    //  - org.bytedeco / com.epam — native BLAS backends. The pure-Java path used
    //    here never loads them.
    //  - org.duckdb — pulled in for SMILE's data-loading conveniences, which nothing
    //    here calls. It ships prebuilt native libraries for four platforms and was
    //    231MB uncompressed, roughly 95% of the shaded JAR. Excluding it takes the
    //    release artefact from 75MB to under 5MB.
    //  - commons-csv — likewise only used by SMILE's I/O layer.
    implementation("com.github.haifengl:smile-core:4.3.0") {
        exclude(group = "org.bytedeco")
        exclude(group = "com.epam")
        exclude(group = "org.duckdb")
        exclude(group = "org.apache.commons", module = "commons-csv")
    }

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

// Use dots instead of spaces in the JAR filename to avoid illegal URI characters
// when QuPath's extension catalog downloads the release asset.
tasks.withType<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar> {
    archiveBaseName.set("FlowPath")
}

tasks.withType<Jar> {
    archiveBaseName.set("FlowPath")
}
