/*
 * (c) Copyright 2025 Palantir Technologies Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.gradle.versions

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.gradle.util.GradleVersion
import spock.lang.Unroll

import static com.palantir.gradle.versions.GradleTestVersions.GRADLE_VERSIONS

@Unroll
class VirtualPlatformSettingsPluginIntegrationSpec extends IntegrationSpec {

    File repo

    void setup() {
        repo = generateMavenRepo(
                "com.external:some-library:1.0.0 -> com.palantir:service-a:1.0.0",
                "com.external:some-other-library:1.0.0 -> com.palantir:service-c:2.0.0"
        )

        // Publish version 1.0.0 with platform constraints
        publishVersion('1.0.0')

        // Publish version 2.0.0 with platform constraints
        publishVersion('2.0.0')
    }

    def '#gradleVersionNumber: buildscript - virtual platform aligns versions when external library pulls in lower version'() {
        setup:
        //language=gradle
        File consumerProject = createBuildscriptConsumerProject("""
            classpath 'com.palantir:service-b:2.0.0'
            classpath 'com.external:some-library:1.0.0'  // pulls in service-a:1.0.0
        """.stripIndent(true))

        when:
        def result = runGradleTask(consumerProject, 'checkBuildscriptVersions', gradleVersionNumber)

        then:
        result.task(':checkBuildscriptVersions').outcome == TaskOutcome.SUCCESS
        result.output.contains("SUCCESS: Buildscript dependencies aligned to version 2.0.0")

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    def '#gradleVersionNumber: buildscript - virtual platform aligns versions when external library pulls in higher version'() {
        setup:
        //language=gradle
        File consumerProject = createBuildscriptConsumerProject("""
            classpath 'com.palantir:service-b:1.0.0'
            classpath 'com.external:some-other-library:1.0.0'  // pulls in service-c:2.0.0
        """.stripIndent(true))

        when:
        def result = runGradleTask(consumerProject, 'checkBuildscriptVersions', gradleVersionNumber)

        then:
        result.task(':checkBuildscriptVersions').outcome == TaskOutcome.SUCCESS
        result.output.contains("SUCCESS: Buildscript dependencies aligned to version 2.0.0")

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    def '#gradleVersionNumber: runtime - virtual platform aligns versions when external library pulls in lower version'() {
        setup:
        //language=gradle
        File consumerProject = createRuntimeConsumerProject("""
            implementation 'com.palantir:service-b:2.0.0'
            implementation 'com.external:some-library:1.0.0'  // pulls in service-a:1.0.0
        """.stripIndent(true))

        when:
        def result = runGradleTask(consumerProject, 'checkRuntimeVersions', gradleVersionNumber)

        then:
        result.task(':checkRuntimeVersions').outcome == TaskOutcome.SUCCESS
        result.output.contains("SUCCESS: Runtime dependencies aligned to version 2.0.0")

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    def '#gradleVersionNumber: runtime - virtual platform aligns versions when external library pulls in higher version'() {
        setup:
        //language=gradle
        File consumerProject = createRuntimeConsumerProject("""
            implementation 'com.palantir:service-b:1.0.0'
            implementation 'com.external:some-other-library:1.0.0'  // pulls in service-c:2.0.0
        """.stripIndent(true))

        when:
        def result = runGradleTask(consumerProject, 'checkRuntimeVersions', gradleVersionNumber)

        then:
        result.task(':checkRuntimeVersions').outcome == TaskOutcome.SUCCESS
        result.output.contains("SUCCESS: Runtime dependencies aligned to version 2.0.0")

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    private void publishVersion(String version) {
        //language=gradle
        buildFile.text = """
            plugins {
                id 'com.palantir.versions-lock'
                id 'com.palantir.add-virtual-platform-plugin'
            }
            
            allprojects {
                group = 'com.palantir'
                version = '${version}'
                
                repositories {
                    maven { url "file:///${repo.absolutePath}" }
                }
            }
            
            subprojects {
                apply plugin: 'java'
                apply plugin: 'maven-publish'
                
                publishing {
                    repositories {
                        maven { url "file:///${repo.absolutePath}" }
                    }
                    publications {
                        maven(MavenPublication) {
                            from components.java
                        }
                    }
                }
            }
        """.stripIndent(true)

        file('gradle.properties').text = 'com.palantir.gradle.versions.addVirtualPlatformConstraint = true'

        if (GradleVersion.current() < GradleVersion.version("6.0")) {
            settingsFile << """
                enableFeaturePreview('GRADLE_METADATA')
            """.stripIndent(true)
        }

        addSubproject('service-a', "// Service A ${version}")
        addSubproject('service-b', "// Service B ${version}")
        addSubproject('service-c', "// Service C ${version}")

        runTasks('--write-locks', 'publish')
    }

    private File createBuildscriptConsumerProject(String dependencies) {
        File consumerProject = File.createTempDir('consumer-project', '')
        consumerProject.deleteOnExit()

        //language=gradle
        new File(consumerProject, 'settings.gradle') << """
            plugins {
                id 'com.palantir.virtual-platform-settings'
            }
            rootProject.name = 'consumer'
        """.stripIndent(true)

        //language=gradle
        new File(consumerProject, 'build.gradle') << """
            buildscript {
                repositories {
                    maven { url "file:///${repo.absolutePath}" }
                }
                dependencies {
                    ${dependencies}
                }
            }
            
            plugins {
                id 'java'
            }
            
            tasks.register('checkBuildscriptVersions') {
                doLast {
                    def buildscriptResolved = [:]
                    buildscript.configurations.classpath.resolvedConfiguration.resolvedArtifacts.each { 
                        if (it.moduleVersion.id.group == 'com.palantir') {
                            buildscriptResolved[it.moduleVersion.id.module] = it.moduleVersion.id.version
                        }
                    }

                    def buildscriptVersions = buildscriptResolved.values() as Set
                    assert buildscriptVersions.size() == 1 : "Buildscript modules should be aligned! Got: \${buildscriptResolved}"
                    assert buildscriptVersions.first() == '2.0.0' : "Should align to latest version, got: \${buildscriptVersions.first()}"
                    println "SUCCESS: Buildscript dependencies aligned to version 2.0.0"
                }
            }
        """.stripIndent(true)

        return consumerProject
    }

    private File createRuntimeConsumerProject(String dependencies) {
        File consumerProject = File.createTempDir('consumer-project', '')
        consumerProject.deleteOnExit()

        //language=gradle
        new File(consumerProject, 'settings.gradle') << """
            plugins {
                id 'com.palantir.virtual-platform-settings'
            }
            rootProject.name = 'consumer'
        """.stripIndent(true)

        //language=gradle
        new File(consumerProject, 'build.gradle') << """
            plugins {
                id 'java'
            }
            
            repositories {
                maven { url "file:///${repo.absolutePath}" }
            }
            
            dependencies {
                ${dependencies}
            }
            
            tasks.register('checkRuntimeVersions') {
                doLast {
                    def runtimeResolved = [:]
                    configurations.runtimeClasspath.resolvedConfiguration.resolvedArtifacts.each { 
                        if (it.moduleVersion.id.group == 'com.palantir') {
                            runtimeResolved[it.moduleVersion.id.module] = it.moduleVersion.id.version
                        }
                    }

                    def runtimeVersions = runtimeResolved.values() as Set
                    assert runtimeVersions.size() == 1 : "Runtime modules should be aligned! Got: \${runtimeResolved}"
                    assert runtimeVersions.first() == '2.0.0' : "Should align to latest version, got: \${runtimeVersions.first()}"
                    println "SUCCESS: Runtime dependencies aligned to version 2.0.0"
                }
            }
        """.stripIndent(true)

        return consumerProject
    }

    private static def runGradleTask(File projectDir, String task, String gradleVersion) {
        return GradleRunner.create()
                .withProjectDir(projectDir)
                .withArguments(task, '--stacktrace')
                .withPluginClasspath()
                .withGradleVersion(gradleVersion)
                .build()
    }
}