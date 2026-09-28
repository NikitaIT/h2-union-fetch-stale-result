plugins {
    java
}

repositories {
    mavenCentral()
}

// Run against another release with: ./gradlew test -Ph2Version=2.4.240
val h2Version = providers.gradleProperty("h2Version").getOrElse("2.5.252")

dependencies {
    implementation("com.h2database:h2:$h2Version")
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = false
    }
    inputs.property("h2Version", h2Version)
    doFirst { println("H2 $h2Version") }
}

tasks.register<JavaExec>("run") {
    description = "Runs the minimal reproduction."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "repro.Main"
}
