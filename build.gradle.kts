plugins {
    id("java")
}

group = "com.xiaohanc"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("net.jqwik:jqwik:1.9.2")

    testImplementation("org.openjdk.jmh:jmh-core:1.37")
    testAnnotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:1.37")
    testImplementation("tools.profiler:async-profiler:4.3")
    testImplementation("net.openhft:affinity:3.23.3")
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("--add-modules")
    options.compilerArgs.add("jdk.incubator.vector")
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--add-modules", "jdk.incubator.vector")
}

tasks.register<JavaExec>("benchmark") {
    mainClass.set("com.xiaohanc.orderbook.OrderBookBenchmark")
    classpath = sourceSets.test.get().runtimeClasspath
    jvmArgs("--enable-native-access=ALL-UNNAMED", "--add-modules", "jdk.incubator.vector")
    if (project.hasProperty("runArgs")) {
        args(project.property("runArgs").toString().split(" "))
    }
}