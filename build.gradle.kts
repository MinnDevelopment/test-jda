import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    idea
    application
    id("com.github.johnrengelman.shadow") version "4.0.4"
    kotlin("jvm") version "1.3.50"
}

application.mainClassName = "Bot"

configure<JavaPluginConvention> {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

repositories {
    jcenter()
    maven("https://jitpack.io")
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))
    implementation(kotlin("scripting-jsr223"))
    implementation("net.dv8tion:jda")
    implementation("org.json:json:20160810")
    implementation("net.sf.trove4j:trove4j:3.0.3")
    implementation("org.slf4j:slf4j-api:1.7.25")
    implementation("com.github.spotbugs:spotbugs:3.1.1")
    implementation("ch.qos.logback:logback-classic:1.2.3")
    implementation("org.codehaus.groovy:groovy-jsr223:2.4.14")
    implementation("io.projectreactor:reactor-core:3.2.5.RELEASE")

    implementation("club.minnced:jda-reactor:+") {
        exclude(module="JDA")
    }
    implementation("com.sedmelluq:lavaplayer:1.3.17") {
        exclude(module="JDA")
    }
    implementation("com.sedmelluq:jda-nas:1.1.0") {
//        exclude(module="JDA")
    }
    implementation("club.minnced:magma:+")
    implementation("club.minnced:jda-reactor:+")
}

val shadowJar: ShadowJar by tasks

shadowJar.apply {
    doLast {
        println(shadowJar.archiveFileName.get())
    }

    minimize()
    classifier = "shadow"
    manifest.attributes("Premain-Class" to "Bot")
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}

tasks.withType<JavaCompile> {
    options.apply {
        encoding = "UTF-8"
        compilerArgs = mutableListOf("-Xlint:deprecation", "-Xlint:unchecked")
    }
}
