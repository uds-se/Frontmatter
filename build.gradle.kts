import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.ajoberstar.grgit.Grgit

plugins {
    kotlin("jvm") version "1.4.21"
    id("com.github.johnrengelman.shadow") version "4.0.2"
    id("maven-publish")
    id("org.ajoberstar.grgit") version "4.1.0"
}

group = "saarland.cispa"
version = "1.0.93-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

publishing {
    publications {
        create<MavenPublication>("main") {
            from(components["kotlin"])
//            artifact(tasks["sourcesJar"])
        }
    }

    repositories {
        mavenLocal()
    }
}

repositories {
    jcenter()
    mavenLocal()
//    maven { url = uri("https://soot-build.cs.uni-paderborn.de/nexus/repository/soot-snapshot/") }
//    maven {
//        url = uri("https://soot-build.cs.uni-paderborn.de/nexus/repository/soot-release/")
//        content {
//            excludeModule("de.fraunhofer.iem", "boomerangPDS")
//        }
//    }
//    maven { url = uri("http://nexus.st.cs.uni-saarland.de:8081/artifactory/libs-snapshot-local/") }
    maven { url = uri("http://www.i3s.unice.fr/~hogie/maven_repository/") }
}

dependencies {
    implementation("de.fraunhofer.iem", "boomerangPDS", "2.5.1k-SNAPSHOT") {
        exclude("ca.mcgill.sable", "soot")
        exclude("org.slf4j", "slf4j-simple")
        exclude("org.slf4j", "slf4j-log4j12")
    }
    implementation("io.github.microutils:kotlin-logging:1.6.22")
    runtimeOnly("org.slf4j:slf4j-log4j12:1.7.25")
    runtimeOnly("org.apache.logging.log4j:log4j-core:2.11.0")
    implementation("ca.mcgill.sable:soot:4.1.0-SNAPSHOT")
    implementation("de.tud.sse:soot-infoflow-android:2.7.3-SNAPSHOT") {
        exclude("ca.mcgill.sable", "soot")
        exclude("org.slf4j", "slf4j-simple")
        exclude("org.slf4j", "slf4j-log4j12")
    }
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.3.3")
    implementation("com.google.code.gson:gson:2.8.5")
    implementation("com.github.ajalt:clikt:2.8.0")
//    implementation("com.github.pemistahl:lingua:0.6.1")
    implementation(files("local_libs/langdetect.jar"))
    implementation("net.arnx:jsonic:1.3.0") //langdetect dependency
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.3.1")
    testImplementation("org.assertj:assertj-core:3.9.1")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:5.3.1")
}

tasks {
    register<Jar>("sourcesJar") {
        archiveClassifier.set("sources")
        from(sourceSets.main.get().allSource)
    }
    withType<Test> {
        useJUnitPlatform()
        systemProperties["android_jar"] = project.ext["android_jar"]
    }
    withType<JavaCompile> {
        options.compilerArgs.add("-Xlint")
        options.isFork = true
    }
    withType<KotlinCompile>().configureEach {
        kotlinOptions.jvmTarget = "11"
    }
    withType<Jar> {
        manifest {
            attributes["Main-Class"] = "saarland.cispa.frontmatter.MainKt"
            attributes["Implementation-Version"] = project.version
        }
    }
}

val sootDir = "$buildDir/soot"
val boomerangDir = "$buildDir/boomerang"
val flowdroidDir = "$buildDir/flowdroid"
val sootRepo = "https://github.com/soot-oss/soot.git"
val boomerangRepo = "https://github.com/CROSSINGTUD/SPDS.git"
val flowdroidRepo = "https://github.com/secure-software-engineering/FlowDroid.git"

task("cloneSoot") {
    description = "Clone soot"
    onlyIf { !file(sootDir).exists()}
    doLast {
        val sootCommit = "c53e51cd406a78ed622d71e0e2082c308b07f4b8"
        val sootPatch = "$projectDir/patches/soot.patch"
        val grgit = Grgit.clone(
            mapOf(
                "dir" to sootDir, "uri" to sootRepo, "checkout" to false,
                "all" to false
            )
        )
        grgit.checkout(mapOf("branch" to sootCommit))
        grgit.apply(mapOf("patch" to sootPatch))
        grgit.close()
    }
}

task<Exec>("installSoot") {
    dependsOn("cloneSoot")
    description = "Build soot artifact"
    workingDir(sootDir)
    commandLine("mvn", "install", "-DskipTests=true")
}

task("cloneBoomerang") {
    description = "Clone Boomerang"
    onlyIf { !file(boomerangDir).exists() }
    doLast {
        val boomerangCommit = "ca61b982878b7c81a674dff5c6909d2bae74bd6e"
        val boomerangPatch = "$projectDir/patches/boomerang.patch"
        val grgit = Grgit.clone(
            mapOf(
                "dir" to boomerangDir, "uri" to boomerangRepo, "checkout" to false,
                "all" to false
            )
        )
        grgit.checkout(mapOf("branch" to boomerangCommit))
        grgit.apply(mapOf("patch" to boomerangPatch))
        grgit.close()
    }
}

task<Exec>("installBoomerang") {
    dependsOn("cloneBoomerang")
    description = "Build boomerang artifact"
    workingDir(boomerangDir)
    commandLine("mvn", "install", "-DskipTests=true")
}

task("cloneFlowdroid") {
    description = "Clone Flowdroid"
    onlyIf { !file(flowdroidDir).exists() }
    doLast {
        val flowdroidCommit = "23e93292e453b3655b4a848271890389e0016e61"
        val flowdroidPatch = "${projectDir}/patches/flowdroid.patch"
        val grgit = Grgit.clone(
            mapOf(
                "dir" to flowdroidDir, "uri" to flowdroidRepo, "checkout" to false,
                "all" to false
            )
        )
        grgit.checkout(mapOf("branch" to flowdroidCommit))
        grgit.apply(mapOf("patch" to flowdroidPatch))
        grgit.close()
    }
}

task<Exec>("installFlowdroid") {
    dependsOn("cloneFlowdroid")
    dependsOn("installSoot")
    description = "Build Flowdroid artifact"
    workingDir(flowdroidDir)
    commandLine("mvn", "install", "-DskipTests=true")
}

task("installDependencies") {
    dependsOn("installSoot")
    dependsOn("installBoomerang")
    dependsOn("installFlowdroid")
    tasks.findByName("installBoomerang")?.mustRunAfter("installSoot")
    tasks.findByName("installFlowdroid")?.mustRunAfter("installSoot")
}
