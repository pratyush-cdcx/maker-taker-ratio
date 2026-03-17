plugins {
    java
    application
    jacoco
}

group = "com.coindcx"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_23
    targetCompatibility = JavaVersion.VERSION_23
}

repositories {
    mavenCentral()
}

val aeronVersion = "1.50.2"
val agronaVersion = "2.1.0"
val sbeVersion = "1.35.6"
val kafkaVersion = "3.9.2"

dependencies {
    implementation("io.aeron:aeron-all:$aeronVersion")
    implementation("org.agrona:agrona:$agronaVersion")
    implementation("org.apache.kafka:kafka-clients:$kafkaVersion")
    implementation("uk.co.real-logic:sbe-all:$sbeVersion")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.slf4j:slf4j-api:2.0.16")
    implementation("ch.qos.logback:logback-classic:1.5.12")
    implementation("org.hdrhistogram:HdrHistogram:2.2.2")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.mockito:mockito-core:5.23.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.23.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("com.coindcx.makertaker.Application")
}

val sbeGenerate by tasks.registering(JavaExec::class) {
    group = "code generation"
    description = "Generate SBE codecs from schema"
    classpath = configurations.compileClasspath.get()
    mainClass.set("uk.co.real_logic.sbe.SbeTool")
    args = listOf("src/main/resources/sbe/schema.xml")
    systemProperty("sbe.output.dir", layout.buildDirectory.dir("generated/src/main/java").get().asFile.absolutePath)
    systemProperty("sbe.target.language", "Java")
    systemProperty("sbe.java.generate.interfaces", "true")
    systemProperty("sbe.target.namespace", "com.coindcx.makertaker.sbe")
    outputs.dir(layout.buildDirectory.dir("generated/src/main/java"))
    inputs.file("src/main/resources/sbe/schema.xml")
}

sourceSets {
    main {
        java {
            srcDir(layout.buildDirectory.dir("generated/src/main/java"))
        }
    }
}

tasks.compileJava {
    dependsOn(sbeGenerate)
    options.compilerArgs.addAll(listOf("--release", "23"))
}

tasks.compileTestJava {
    options.compilerArgs.addAll(listOf("--release", "23"))
}

tasks.test {
    useJUnitPlatform()
    jvmArgs(
        "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",
        "--add-opens", "java.base/java.util=ALL-UNNAMED",
        "--add-opens", "java.base/java.lang=ALL-UNNAMED",
        "--add-opens", "java.base/java.lang.invoke=ALL-UNNAMED",
        "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
        "--add-opens", "java.base/java.nio=ALL-UNNAMED"
    )
}

jacoco {
    toolVersion = "0.8.14"
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
    classDirectories.setFrom(
        files(classDirectories.files.map {
            fileTree(it) {
                exclude(
                    "com/coindcx/makertaker/sbe/**",
                    "com/coindcx/makertaker/benchmark/**",
                    "com/coindcx/makertaker/Application.class"
                )
            }
        })
    )
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.jacocoTestReport)
    classDirectories.setFrom(
        files(classDirectories.files.map {
            fileTree(it) {
                exclude(
                    "com/coindcx/makertaker/sbe/**",
                    "com/coindcx/makertaker/benchmark/**",
                    "com/coindcx/makertaker/Application.class"
                )
            }
        })
    )
    violationRules {
        rule {
            limit {
                minimum = "0.95".toBigDecimal()
            }
        }
    }
}

tasks.jar {
    manifest {
        attributes("Main-Class" to "com.coindcx.makertaker.Application")
    }
}

tasks.register<Jar>("fatJar") {
    group = "build"
    description = "Build a fat JAR with all dependencies"
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes("Main-Class" to "com.coindcx.makertaker.Application")
    }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith("jar") }
            .map { zipTree(it) }
    })
}
