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

import com.fasterxml.jackson.databind.ObjectMapper
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.gradle.util.GradleVersion
import spock.lang.Unroll

import static com.palantir.gradle.versions.GradleTestVersions.GRADLE_VERSIONS

@Unroll
class GradleModuleMetadataConstraintsPluginIntegrationSpec extends IntegrationSpec {

    File repo

    void setup() {
        // Setup external repository with only external libraries
        repo = generateMavenRepo(
                // External library that depends on service-a:1.0.0
                "com.external:some-library:1.0.0 -> com.palantir:service-a:1.0.0",
        )

        // First, publish version 1.0.0 WITH platform constraints
        setupAndPublishVersion1WithPlatformConstraints()

        // Now prepare for version 2.0.0 tests
        setupVersion2()
    }

    private void setupAndPublishVersion1WithPlatformConstraints() {
        //language=gradle
        buildFile << """
            plugins {
                id 'com.palantir.versions-lock'
            }
            
            allprojects {
                group = 'com.palantir'
                version = '1.0.0'
                
                repositories {
                    maven { url "file:///${repo.absolutePath}" }
                }
            }
            
            subprojects {
                // Apply plugins based on project type
                if (project.name == 'platform-bom') {
                    apply plugin: 'java-platform'
                } else {
                    apply plugin: 'java'
                }
                
                apply plugin: 'maven-publish'
                
                publishing {
                    repositories {
                        maven {
                            url "file:///${repo.absolutePath}"
                        }
                    }
                    publications {
                        maven(MavenPublication) {
                            if (project.name == 'platform-bom') {
                                from components.javaPlatform
                            } else {
                                from components.java
                            }
                        }
                    }
                }
            }
        """.stripIndent(true)

        // Create BOM subproject
        //language=gradle
        addSubproject('platform-bom', '''
            // BOM project
            dependencies {
                constraints {
                    api project(':service-a')
                    api project(':service-b')
                }
            }
            
            javaPlatform {
                allowDependencies()
            }
        '''.stripIndent(true))

        // Create service subprojects with BOM dependency
        //language=gradle
        addSubproject('service-a', '''
            // Service A v1
            dependencies {
                implementation platform(project(':platform-bom'))
            }
        '''.stripIndent(true))

        //language=gradle
        addSubproject('service-b', '''
            // Service B v1
            dependencies {
                implementation platform(project(':platform-bom'))
            }
        '''.stripIndent(true))

        // Ensure GMM is enabled for Gradle < 6.0
        if (GradleVersion.current() < GradleVersion.version("6.0")) {
            settingsFile << '''
                enableFeaturePreview('GRADLE_METADATA')
            '''.stripIndent(true)
        }

        // Publish version 1.0.0 WITH platform constraints
        runTasks('--write-locks')
        runTasks('publish')

    }

    private void setupVersion2() {
        // Update to version 2.0.0 for subsequent tests
        buildFile.text = buildFile.text.replace("version = '1.0.0'", "version = '2.0.0'")

        // Update service implementations (simulate actual changes)
        file('service-a/src/main/java/ServiceA.java') << '// Service A v2'
        file('service-b/src/main/java/ServiceB.java') << '// Service B v2'

        // Update BOM version references if needed
        //language=gradle
        file('platform-bom/build.gradle').text = '''
            // BOM project v2
            dependencies {
                constraints {
                    api project(':service-a')
                    api project(':service-b')
                }
            }
            
            javaPlatform {
                allowDependencies()
            }
        '''.stripIndent(true)
    }

    def '#gradleVersionNumber: publishPlatformConstraints=true prevents version skew when consumer uses newer version'() {
        setup:

        if (GradleVersion.version(gradleVersionNumber) < GradleVersion.version("6.0")) {
            settingsFile << '''
                enableFeaturePreview('GRADLE_METADATA')
            '''.stripIndent(true)
        }

        runTasks('--write-locks')
        runTasks('publish')

        // Create consumer project that directly depends on newer version
        //language=gradle
        File consumerProject = createConsumerProject('''
            dependencies {
                implementation 'com.palantir:service-b:2.0.0'
                implementation 'com.external:some-library:1.0.0'  // pulls in service-a:1.0.0
            }
        '''.stripIndent(true))

        when:
        def result = GradleRunner.create()
                .withProjectDir(consumerProject)
                .withArguments('checkVersions')
                .withPluginClasspath()
                .withGradleVersion(gradleVersionNumber)
                .build()

        then:
        result.task(':checkVersions').outcome == TaskOutcome.SUCCESS
        result.output.contains("SUCCESS: All modules aligned to version 2.0.0")

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    def '#gradleVersionNumber: publishPlatformConstraints=true aligns all modules to forced lower version'() {
        setup:
        if (GradleVersion.version(gradleVersionNumber) < GradleVersion.version("6.0")) {
            settingsFile << '''
                enableFeaturePreview('GRADLE_METADATA')
            '''.stripIndent(true)
        }

        runTasks('--write-locks')
        runTasks('publish')

        // Create consumer project that forces a downgrade of one module
        // Platform constraints should align all others
        //language=gradle
        File consumerProject = createConsumerProject('''
            dependencies {
                implementation 'com.palantir:service-a:2.0.0'
                implementation 'com.palantir:service-b:2.0.0'
            }
           
            configurations.all {
                resolutionStrategy {
                    force 'com.palantir:service-b:1.0.0'
                }
            }
        '''.stripIndent(true))

        when:
        def result = GradleRunner.create()
                .withProjectDir(consumerProject)
                .withArguments('checkVersionsDowngrade')
                .withPluginClasspath()
                .withGradleVersion(gradleVersionNumber)
                .build()

        then:
        result.task(':checkVersionsDowngrade').outcome == TaskOutcome.SUCCESS
        result.output.contains("SUCCESS: All modules aligned to version 1.0.0")

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    private File createConsumerProject(String dependenciesBlock) {
        File consumerProject = File.createTempDir('consumer-project', '')
        consumerProject.deleteOnExit()
        consumerProject.mkdirs()

        new File(consumerProject, 'settings.gradle') << "rootProject.name = 'consumer'"

        //language=gradle
        new File(consumerProject, 'build.gradle') << """
            plugins {
                id 'java'
            }
            
            repositories {
                maven { 
                    url "file:///${repo.absolutePath}"
                }
            }
            
            ${dependenciesBlock}
            
            tasks.register('checkVersions') {
                doLast {
                    def resolved = [:]
                    configurations.runtimeClasspath.resolvedConfiguration.resolvedArtifacts.each { 
                        if (it.moduleVersion.id.group == 'com.palantir') {
                            resolved[it.moduleVersion.id.module] = it.moduleVersion.id.version
                        }
                    }
                    
                    def versions = resolved.values() as Set
                    assert versions.size() == 1 : "Modules should be aligned! Got: \${versions.sort()}"
                    assert versions.first() == '2.0.0' : "Should align to latest version, got: \${versions.first()}"
                    println "SUCCESS: All modules aligned to version 2.0.0"
                }
            }
            
            tasks.register('checkVersionsDowngrade') {
                doLast {
                    def resolved = [:]
                    configurations.runtimeClasspath.resolvedConfiguration.resolvedArtifacts.each { 
                        if (it.moduleVersion.id.group == 'com.palantir') {
                            resolved[it.moduleVersion.id.module] = it.moduleVersion.id.version
                        }
                    }
                    
                    def versions = resolved.values() as Set
                    assert versions.size() == 1 : "Modules should be aligned! Got: \${versions.sort()}"
                    assert versions.first() == '1.0.0' : "Should align to forced lower version, got: \${versions.first()}"
                    println "SUCCESS: All modules aligned to version 1.0.0"
                }
            }
        """.stripIndent(true)

        return consumerProject
    }
}
