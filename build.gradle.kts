plugins {
    id("com.gradleup.shadow") version "8.3.5"
    id("qupath-conventions")
}

qupathExtension {
    name = "FlowPath"
    // The publishing coordinate for this extension. NOT io.github.qupath -- that is
    // QuPath's own group, inherited from the extension template and never changed.
    group = "io.github.sceriff0"
    version = "0.9.0"
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
    //  - org.bytedeco / com.epam — native BLAS/ARPACK backends. FlowPath declares no
    //    bytedeco artefact of its own, and excluding the group here keeps SMILE from
    //    adding one.
    //
    //    Verified, by resolving testRuntimeClasspath: bytedeco still appears on it, via
    //    QuPath's own qupath-core-processing -> opencv-platform -> openblas-platform ->
    //    openblas. ARPACK is not among those artefacts. Expected, but NOT verified
    //    against a packaged QuPath install: the same is true of the extension's real
    //    runtime classpath, i.e. that whatever bytedeco reaches the running extension
    //    comes from the host application rather than from here, and still does not
    //    include ARPACK.
    //
    //    Either way the UMAP path deliberately does not depend on any of it. SMILE's
    //    spectral embedding initialisation is the one thing that would call ARPACK, and
    //    EmbeddingInitialisation steers the neighbour graph so SMILE takes its pure-Java
    //    PCA branch instead. Adding the natives back is not an option rather than a
    //    preference — Maven Central publishes ARPACK for linux-arm64, linux-x86_64,
    //    macosx-x86_64 and windows-x86_64, and no macosx-arm64 at all.
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
    // logback-classic is already on the runtime classpath transitively (via
    // libs.bundles.logging on the shadow configuration); this makes its ListAppender
    // available at *test compile time* too, for tests that assert a warning was logged
    // (e.g. MarkerPositivityCanvasTest's malformed-gate-group case) rather than only
    // asserting on the resulting numbers.
    testImplementation("ch.qos.logback:logback-classic:1.5.23")
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
