/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

import com.github.spotbugs.SpotBugsPlugin
import com.github.spotbugs.SpotBugsTask
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import versions.BuildToolVersions

plugins {
    java
    jacoco
    checkstyle
    id("org.nosphere.apache.rat") version "0.4.0"
    id("com.github.ethankhall.semantic-versioning") version "1.1.0"
    id("com.github.spotbugs") version "1.6.10"
    witness
    signing
    publishing
    // Automatic publish to Nexus repository. Versions are specified in `apache-release` plugin
    id("io.codearte.nexus-staging")
    id("de.marcphilipp.nexus-publish")
}

with(version as io.ehdev.version.Version) {
    major = 5
    minor = 2
    patch = 0
    releaseBuild = true
}

apply(from = "$rootDir/gradle/dependencyVerification.gradle.kts")
apply(from = "$rootDir/gradle/release.gradle.kts")

// Do not enable spotbugs by default. Execute it only when -PenableSpotBugs (or -DenableSpotbugs) is present
fun Project.enableSpotBugs() = hasProperty("enableSpotBugs") || hasProperty("enableSpotbugs")
fun reportsForHumans() = !(System.getenv()["CI"]?.toBoolean() ?: false)

val lastEditYear by extra {
    file("$rootDir/NOTICE")
            .readLines()
            .first { it.contains("Copyright") }
            .let {
                """Copyright \d{4}-(\d{4})""".toRegex()
                        .find(it)?.groupValues?.get(1)
                        ?: throw IllegalStateException("Unable to identify copyright year from $rootDir/NOTICE")
            }
}

tasks.withType<org.nosphere.apache.rat.RatTask>().configureEach {
    excludes.set(rootDir.resolve("rat-excludes.txt").readLines())
}

val jacocoReport by tasks.registering(JacocoReport::class) {
    group = "Coverage reports"
    description = "Generates an aggregate report from all subprojects"
}

allprojects {
    group = "org.apache.jmeter"
    // JMeter ClassFinder parses "class.path" and tries to find jar names there,
    // so we should produce jars without versions names for now
    // version = rootProject.version
    apply<CheckstylePlugin>()
    apply<SigningPlugin>()
    apply<SpotBugsPlugin>()

    checkstyle {
        toolVersion = BuildToolVersions.checkstyle
    }

    spotbugs {
        toolVersion = BuildToolVersions.spotbugs
    }

    plugins.withType<JacocoPlugin> {
        the<JacocoPluginExtension>().toolVersion = BuildToolVersions.jacoco

        val testTasks = tasks.withType<Test>()
        testTasks.configureEach {
            extensions.configure<JacocoTaskExtension> {
                // We don't want to collect coverage for third-party classes
                includes?.add("org.apache.jmeter.*")
            }
        }

        jacocoReport {
            // Note: this creates a lazy collection
            // Some of the projects might fail to create a file (e.g. no tests or no coverage),
            // So we check for file existence. Otherwise JacocoMerge would fail
            val execFiles = files(testTasks).filter { it.exists() && it.name.endsWith(".exec") }
            executionData(execFiles)
        }

        tasks.withType<JacocoReport>().configureEach {
            reports {
                html.isEnabled = reportsForHumans()
                xml.isEnabled = !reportsForHumans()
            }
        }
        // Add each project to combined report
        configure<SourceSetContainer> {
            val mainCode = main.get()
            jacocoReport.configure {
                additionalSourceDirs.from(mainCode.allJava.srcDirs)
                sourceDirectories.from(mainCode.allSource.srcDirs)
                // IllegalStateException: Can't add different class with same name: module-info
                // https://github.com/jacoco/jacoco/issues/858
                classDirectories.from(mainCode.output.asFileTree.matching {
                    exclude("module-info.class")
                })
            }
        }
    }

    tasks.withType<AbstractArchiveTask>().configureEach  {
        // Ensure builds are reproducible
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        dirMode = "775".toInt(8)
        fileMode = "664".toInt(8)
    }

    // Not all the modules use publishing plugin
    plugins.withType<PublishingPlugin> {
        // Sign all the published artifacts
        signing {
            sign(publishing.publications)
        }
    }

    plugins.withType<JavaPlugin> {
        // This block is executed right after `java` plugin is added to a project

        repositories {
            jcenter()
            ivy {
                url = uri("https://github.com/bulenkov/Darcula/raw/")
                patternLayout {
                    artifact("[revision]/build/[module].[ext]")
                }
                metadataSources {
                    artifact() // == don't try downloading .pom file from the repository
                }
            }
        }

        tasks {
            withType<JavaCompile>().configureEach {
                options.encoding = "UTF-8"
            }
            withType<ProcessResources>().configureEach  {
                // apply native2ascii conversion since Java 8 expects properties to have ascii symbols only
                from(source) {
                    include("**/*.properties")
                    filteringCharset = "UTF-8"
                    filter(org.apache.tools.ant.filters.EscapeUnicode::class)
                }
            }
            withType<Jar>().configureEach  {
                into("META-INF") {
                    from("$rootDir/LICENSE")
                    from("$rootDir/NOTICE")
                }
                manifest {
                    attributes["Specification-Title"] = "Apache JMeter"
                    attributes["Specification-Vendor"] = "Apache Software Foundation"
                    attributes["Implementation-Vendor"] = "Apache Software Foundation"
                    attributes["Implementation-Vendor-Id"] = "org.apache"
                    attributes["Implementation-Version"] = project.version
                }
            }
            withType<Test>().configureEach  {
                testLogging {
                    exceptionFormat = TestExceptionFormat.FULL
                }
                // Pass the property to tests
                systemProperty("java.awt.headless", System.getProperty("java.awt.headless"))
            }
            withType<SpotBugsTask>().configureEach  {
                reports {
                    html.isEnabled = reportsForHumans()
                    xml.isEnabled = !reportsForHumans()
                }
                enabled = enableSpotBugs()
            }
            withType<Javadoc>().configureEach  {
                (options as StandardJavadocDocletOptions).apply {
                    noTimestamp.value = true
                    showFromProtected()
                    docEncoding = "UTF-8"
                    charSet = "UTF-8"
                    encoding = "UTF-8"
                    docTitle = "Apache JMeter ${project.name} API"
                    windowTitle = "Apache JMeter ${project.name} API"
                    header = "<b>Apache JMeter</b>"
                    bottom = "Copyright © 1998-$lastEditYear Apache Software Foundation. All Rights Reserved."
                    links("https://docs.oracle.com/javase/8/docs/api/")
                }
            }
        }

        configure<JavaPluginConvention> {
            sourceCompatibility = JavaVersion.VERSION_1_8
        }
    }
}


