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

        // Publish versions to the repo
        publishVersionToRepo('1.0.0')
        publishVersionToRepo('2.0.0')

        // Now setup the main project as the consumer
        setupConsumerProject()
    }

    def '#gradleVersionNumber: buildscript - virtual platform aligns versions when external library pulls in lower version'() {
        setup:
        //language=gradle
        buildFile.text = """
            buildscript {
                repositories {
                    maven { url "file:///${repo.absolutePath}" }
                }
                dependencies {
                    classpath 'com.palantir:service-b:2.0.0'
                    classpath 'com.external:some-library:1.0.0'  // pulls in service-a:1.0.0
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
                    println "SUCCESS: Buildscript dependencies aligned to version \${buildscriptVersions.first()}"
                }
            }
        """.stripIndent(true)

        when:
        def result = runTasks('checkBuildscriptVersions')

        then:
        result.task(':checkBuildscriptVersions').outcome == TaskOutcome.SUCCESS
        result.output.contains("SUCCESS: Buildscript dependencies aligned to version 2.0.0")

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    def '#gradleVersionNumber: buildscript - virtual platform aligns versions when external library pulls in higher version'() {
        setup:
        //language=gradle
        buildFile.text = """
            buildscript {
                repositories {
                    maven { url "file:///${repo.absolutePath}" }
                }
                dependencies {
                    classpath 'com.palantir:service-b:1.0.0'
                    classpath 'com.external:some-other-library:1.0.0'  // pulls in service-c:2.0.0
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
                    println "SUCCESS: Buildscript dependencies aligned to version \${buildscriptVersions.first()}"
                }
            }
        """.stripIndent(true)

        when:
        def result = runTasks('checkBuildscriptVersions')

        then:
        result.task(':checkBuildscriptVersions').outcome == TaskOutcome.SUCCESS
        result.output.contains("SUCCESS: Buildscript dependencies aligned to version 2.0.0")

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    def '#gradleVersionNumber: buildscript - virtual platform aligns versions when resolution forces in lower version'() {
        setup:
        //language=gradle
        buildFile.text = """
            buildscript {
                repositories {
                    maven { url "file:///${repo.absolutePath}" }
                }
                dependencies {
                    classpath 'com.palantir:service-a:2.0.0'
                    classpath 'com.palantir:service-b:2.0.0'
                }
                
                configurations.classpath {
                    resolutionStrategy {
                        force 'com.palantir:service-b:1.0.0'
                    }
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
                    println "SUCCESS: Buildscript dependencies aligned to version \${buildscriptVersions.first()}"
                }
            }
        """.stripIndent(true)

        when:
        def result = runTasks('checkBuildscriptVersions')

        then:
        result.task(':checkBuildscriptVersions').outcome == TaskOutcome.SUCCESS
        result.output.contains("SUCCESS: Buildscript dependencies aligned to version 2.0.0")

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    def '#gradleVersionNumber: runtime - virtual platform aligns versions when external library pulls in lower version'() {
        setup:
        //language=gradle
        buildFile.text = """
            plugins {
                id 'java'
            }
            
            repositories {
                maven { url "file:///${repo.absolutePath}" }
            }
            
            dependencies {
                implementation 'com.palantir:service-b:2.0.0'
                implementation 'com.external:some-library:1.0.0'  // pulls in service-a:1.0.0
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
                    println "SUCCESS: Runtime dependencies aligned to version \${runtimeVersions.first()}"
                }
            }
        """.stripIndent(true)

        when:
        def result = runTasks('checkRuntimeVersions')

        then:
        result.task(':checkRuntimeVersions').outcome == TaskOutcome.SUCCESS
        result.output.contains("SUCCESS: Runtime dependencies aligned to version 2.0.0")

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    def '#gradleVersionNumber: runtime - virtual platform aligns versions when external library pulls in higher version'() {
        setup:
        //language=gradle
        buildFile.text = """
            plugins {
                id 'java'
            }
            
            repositories {
                maven { url "file:///${repo.absolutePath}" }
            }
            
            dependencies {
                implementation 'com.palantir:service-b:1.0.0'
                implementation 'com.external:some-other-library:1.0.0'  // pulls in service-c:2.0.0
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
                    println "SUCCESS: Runtime dependencies aligned to version \${runtimeVersions.first()}"
                }
            }
        """.stripIndent(true)

        when:
        def result = runTasks('checkRuntimeVersions')

        then:
        result.task(':checkRuntimeVersions').outcome == TaskOutcome.SUCCESS
        result.output.contains("SUCCESS: Runtime dependencies aligned to version 2.0.0")

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    def '#gradleVersionNumber: runtime - virtual platform aligns versions when resolution forces in lower version'() {
        setup:
        //language=gradle
        buildFile.text = """
            plugins {
                id 'java'
            }
            
            repositories {
                maven { url "file:///${repo.absolutePath}" }
            }
            
            dependencies {
                implementation 'com.palantir:service-a:2.0.0'
                implementation 'com.palantir:service-b:2.0.0'
            }
            
            configurations.runtimeClasspath {
                resolutionStrategy {
                    force 'com.palantir:service-b:1.0.0'
                }
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
                    println "SUCCESS: Runtime dependencies aligned to version \${runtimeVersions.first()}"
                }
            }
        """.stripIndent(true)

        when:
        def result = runTasks('checkRuntimeVersions')

        then:
        result.task(':checkRuntimeVersions').outcome == TaskOutcome.SUCCESS
        result.output.contains("SUCCESS: Runtime dependencies aligned to version 1.0.0")

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    private void setupConsumerProject() {
        //language=gradle
        settingsFile.text = """
            plugins {
                id 'com.palantir.virtual-platform-settings'
            }
            rootProject.name = 'consumer'
        """.stripIndent(true)
    }

    private void publishVersionToRepo(String version) {
        File publisherProject = File.createTempDir('publisher-project', '')
        publisherProject.deleteOnExit()

        //language=gradle
        new File(publisherProject, 'settings.gradle') << """
            rootProject.name = 'publisher'
            include 'service-a', 'service-b', 'service-c'
        """.stripIndent(true)

        //language=gradle
        new File(publisherProject, 'build.gradle') << """
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

        new File(publisherProject, 'gradle.properties').text =
                'com.palantir.gradle.versions.addVirtualPlatformConstraint = true'

        ['service-a', 'service-b', 'service-c'].each { serviceName ->
            File serviceDir = new File(publisherProject, serviceName)
            serviceDir.mkdirs()
            new File(serviceDir, 'build.gradle') << "// ${serviceName} ${version}"
        }

        runGradleTaskForPublishing(publisherProject, '--write-locks')
        runGradleTaskForPublishing(publisherProject, 'publish')
    }

    private static def runGradleTaskForPublishing(File projectDir, String task) {
        return GradleRunner.create()
                .withProjectDir(projectDir)
                .withArguments(task, '--stacktrace')
                .withPluginClasspath()
                .build()
    }
}