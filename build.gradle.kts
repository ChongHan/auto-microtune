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
}

tasks.register<JavaExec>("benchmark") {
    mainClass.set("com.xiaohanc.orderbook.OrderBookBenchmark")
    classpath = sourceSets.test.get().runtimeClasspath
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    if (project.hasProperty("runArgs")) {
        args(project.property("runArgs").toString().split(" "))
    }
}

tasks.test {
    useJUnitPlatform()
}