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
        // Setup external repository with older versions of all services
        repo = generateMavenRepo(
                "com.palantir:service-a:1.0.0",
                "com.palantir:service-b:1.0.0",
                "com.palantir:service-c:1.0.0",
                // External library that depends on older service-a
                "com.external:some-library:1.0.0 -> com.palantir:service-a:1.0.0",
                // External library that depends on newer service-c (2.0.0 will be published later)
                "com.external:some-other-library:1.0.0 -> com.palantir:service-c:2.0.0"
        )

        //language=gradle
        buildFile << """
            plugins {
                id 'com.palantir.versions-lock'
                id 'com.palantir.gradle-module-metadata-constraints-plugin'
            }
            
            allprojects {
                group = 'com.palantir'
                version = '2.0.0'
                
                repositories {
                    maven { url "file:///${repo.absolutePath}" }
                }
            }
            
            subprojects {
                apply plugin: 'java'
                apply plugin: 'maven-publish'
                
                publishing {
                    repositories {
                        maven {
                            url "file:///${repo.absolutePath}"
                        }
                    }
                    publications {
                        maven(MavenPublication) {
                            from components.java
                        }
                    }
                }
            }
        """.stripIndent(true)

        // Create service subprojects
        addSubproject('service-a', '// Service A')
        addSubproject('service-b', '// Service B')
        addSubproject('service-c', '// Service C')
    }

    def "#gradleVersionNumber: published constraints with platform constraints include all services"() {
        setup:
        file('gradle.properties') << 'com.palantir.gradle.versions.publishPlatformConstraints = true'

        if (GradleVersion.version(gradleVersionNumber) < GradleVersion.version("6.0")) {
            settingsFile << '''
                enableFeaturePreview('GRADLE_METADATA')
            '''.stripIndent(true)
        }

        runTasks('--write-locks')

        when:
        runTasks('generatePomFileForMavenPublication', 'generateMetadataFileForMavenPublication')

        def serviceAConstraint = new MetadataFile.Dependency(
                group: 'com.palantir',
                module: 'service-a',
                version: [requires: '2.0.0'])
        def serviceBConstraint = new MetadataFile.Dependency(
                group: 'com.palantir',
                module: 'service-b',
                version: [requires: '2.0.0'])
        def serviceCConstraint = new MetadataFile.Dependency(
                group: 'com.palantir',
                module: 'service-c',
                version: [requires: '2.0.0'])

        then: "service-a's metadata file has platform constraints for all other services"
        def serviceAMetadataFilename = new File(projectDir, "service-a/build/publications/maven/module.json")
        def serviceAMetadata = new ObjectMapper().readValue(serviceAMetadataFilename, MetadataFile)

        serviceAMetadata.variants == [
                new MetadataFile.Variant(
                        name: 'runtimeElements',
                        dependencies: null,
                        dependencyConstraints: [serviceBConstraint, serviceCConstraint] as Set),
                new MetadataFile.Variant(
                        name: 'apiElements',
                        dependencies: null,
                        dependencyConstraints: [serviceBConstraint, serviceCConstraint] as Set)
        ] as Set

        and: "service-b's metadata file has platform constraints for all other services"
        def serviceBMetadataFilename = new File(projectDir, "service-b/build/publications/maven/module.json")
        def serviceBMetadata = new ObjectMapper().readValue(serviceBMetadataFilename, MetadataFile)

        serviceBMetadata.variants == [
                new MetadataFile.Variant(
                        name: 'runtimeElements',
                        dependencies: null,
                        dependencyConstraints: [serviceAConstraint, serviceCConstraint] as Set),
                new MetadataFile.Variant(
                        name: 'apiElements',
                        dependencies: null,
                        dependencyConstraints: [serviceAConstraint, serviceCConstraint] as Set),
        ] as Set

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    def '#gradleVersionNumber: publishPlatformConstraints=true prevents version skew when consumer uses newer version'() {
        setup:
        file('gradle.properties') << 'com.palantir.gradle.versions.publishPlatformConstraints = true'

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

    def '#gradleVersionNumber: publishPlatformConstraints=true prevents version skew when external library bumps version'() {
        setup:
        file('gradle.properties') << 'com.palantir.gradle.versions.publishPlatformConstraints = true'

        if (GradleVersion.version(gradleVersionNumber) < GradleVersion.version("6.0")) {
            settingsFile << '''
                enableFeaturePreview('GRADLE_METADATA')
            '''.stripIndent(true)
        }

        runTasks('--write-locks')
        runTasks('publish')

        // Create consumer project that depends on older version, but external lib pulls newer
        //language=gradle
        File consumerProject = createConsumerProject('''
            dependencies {
                implementation 'com.palantir:service-b:1.0.0'  // directly depends on old version
                implementation 'com.external:some-other-library:1.0.0'  // pulls in service-c:2.0.0
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

    def '#gradleVersionNumber: publishPlatformConstraints=true aligns all platform modules when any is bumped'() {
        setup:
        file('gradle.properties') << 'com.palantir.gradle.versions.publishPlatformConstraints = true'

        if (GradleVersion.version(gradleVersionNumber) < GradleVersion.version("6.0")) {
            settingsFile << '''
                enableFeaturePreview('GRADLE_METADATA')
            '''.stripIndent(true)
        }

        runTasks('--write-locks')
        runTasks('publish')

        // Create consumer project with complex dependency graph
        //language=gradle
        File consumerProject = createConsumerProject('''
            dependencies {
                implementation 'com.palantir:service-a:1.0.0'
                implementation 'com.palantir:service-b:1.0.0'
                implementation 'com.external:some-other-library:1.0.0'  // pulls in service-c:2.0.0
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

    def '#gradleVersionNumber: publishPlatformConstraints=false allows version skew between modules'() {
        setup:
        file('gradle.properties') << 'com.palantir.gradle.versions.publishPlatformConstraints = false'

        if (GradleVersion.version(gradleVersionNumber) < GradleVersion.version("6.0")) {
            settingsFile << '''
                enableFeaturePreview('GRADLE_METADATA')
            '''.stripIndent(true)
        }

        runTasks('--write-locks')
        runTasks('publish')

        // Create consumer project with mixed versions
        //language=gradle
        File consumerProject = createConsumerProject('''
            dependencies {
                implementation 'com.palantir:service-b:1.0.0'
                implementation 'com.external:some-other-library:1.0.0'  // pulls in service-c:2.0.0
            }
        '''.stripIndent(true))

        when:
        def result = GradleRunner.create()
                .withProjectDir(consumerProject)
                .withArguments('checkVersions')
                .withPluginClasspath()
                .withGradleVersion(gradleVersionNumber)
                .buildAndFail()

        then:
        result.task(':checkVersions').outcome == TaskOutcome.FAILED
        result.output.contains("Modules should be aligned!")

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
        """.stripIndent(true)

        return consumerProject
    }
}
