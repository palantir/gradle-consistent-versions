/*
 * (c) Copyright 2019 Palantir Technologies Inc. All rights reserved.
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
import nebula.test.dependencies.DependencyGraph
import nebula.test.dependencies.GradleDependencyGenerator
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.gradle.util.GradleVersion
import spock.lang.Unroll

import static com.palantir.gradle.versions.GradleTestVersions.GRADLE_VERSIONS
import static com.palantir.gradle.versions.PomUtils.makePlatformPom

@Unroll
class GradleModuleMetadataConstraintsPluginIntegrationSpec extends IntegrationSpec {

    void setup() {
        //language=gradle
        buildFile << '''
            plugins {
                id 'com.palantir.versions-lock'
                id 'com.palantir.gradle-module-metadata-constraints-plugin'
            }
        '''
    }

    def "#gradleVersionNumber: published constraints with platform constraints only"() {
        setup:
        // Test with platform constraints enabled
        file('gradle.properties') << 'com.palantir.gradle.versions.publishPlatformConstraints = true'
        gradleVersion = gradleVersionNumber

        //language=gradle
        buildFile << '''
            allprojects {
                apply plugin: 'java'
            }
        '''.stripIndent(true)

        String publish = '''
            apply plugin: 'maven-publish'
            group = 'com.palantir.same-group'
            version = '2.0.0'
            publishing.publications {
                maven(MavenPublication) {
                    from components.java
                }
            }
        '''.stripIndent(true)

        addSubproject('service-a', """
            $publish
        """.stripIndent(true))

        addSubproject('service-b', """
            $publish
        """.stripIndent(true))

        if (GradleVersion.version(gradleVersionNumber) < GradleVersion.version("6.0")) {
            settingsFile << '''
                enableFeaturePreview('GRADLE_METADATA')
            '''.stripIndent(true)
        }

        runTasks('--write-locks')

        when:
        runTasks('generatePomFileForMavenPublication', 'generateMetadataFileForMavenPublication')

        def serviceAConstraint = new MetadataFile.Dependency(
                group: 'com.palantir.same-group',
                module: 'service-a',
                version: [requires: '2.0.0'])
        def serviceBConstraint = new MetadataFile.Dependency(
                group: 'com.palantir.same-group',
                module: 'service-b',
                version: [requires: '2.0.0'])

        then: "service-a's metadata file has platform constraints and filtered lock file constraints"
        def serviceAMetadataFilename = new File(projectDir, "service-a/build/publications/maven/module.json")
        def serviceAMetadata = new ObjectMapper().readValue(serviceAMetadataFilename, MetadataFile)

        serviceAMetadata.variants == [
                new MetadataFile.Variant(
                        name: 'runtimeElements',
                        dependencies: null,
                        dependencyConstraints: [serviceBConstraint] as Set),
                new MetadataFile.Variant(
                        name: 'apiElements',
                        dependencies: null,
                        dependencyConstraints: [serviceBConstraint] as Set)
        ] as Set

        and: "service-b's metadata file has platform constraints and filtered lock file constraints"
        def serviceBMetadataFilename = new File(projectDir, "service-b/build/publications/maven/module.json")
        def serviceBMetadata = new ObjectMapper().readValue(serviceBMetadataFilename, MetadataFile)

        serviceBMetadata.variants == [
                new MetadataFile.Variant(
                        name: 'runtimeElements',
                        dependencies: null,
                        dependencyConstraints: [serviceAConstraint] as Set),
                new MetadataFile.Variant(
                        name: 'apiElements',
                        dependencies: null,
                        dependencyConstraints: [serviceAConstraint] as Set),
        ] as Set

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    def '#gradleVersionNumber: publishPlatformConstraints=true prevents version skew between modules from same repository'() {
        setup:
        setupVersionAlignmentTest()
        file('gradle.properties') << 'com.palantir.gradle.versions.publishPlatformConstraints = true'

        if (GradleVersion.version(gradleVersionNumber) < GradleVersion.version("6.0")) {
            settingsFile << '''
                enableFeaturePreview('GRADLE_METADATA')
            '''.stripIndent(true)
        }

        when:
        def result = runTasks(':consumer-test:checkVersions')

        then:
        result.tasks(TaskOutcome.SUCCESS).path.contains(':consumer-test:checkVersions')
        result.output.contains("SUCCESS: Both modules aligned to version 2.0.0")

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    def '#gradleVersionNumber: publishPlatformConstraints=false have version skew between modules from same repository'() {
        setup:
        setupVersionAlignmentTest()
        file('gradle.properties') << 'com.palantir.gradle.versions.publishPlatformConstraints = false'

        if (GradleVersion.version(gradleVersionNumber) < GradleVersion.version("6.0")) {
            settingsFile << '''
                enableFeaturePreview('GRADLE_METADATA')
            '''.stripIndent(true)
        }

        when:
        def result = runTasksAndFail(':consumer-test:checkVersions')

        then:
        result.tasks(TaskOutcome.FAILED).path.contains(':consumer-test:checkVersions')
        result.output.contains("Modules should be aligned! Got: [2.0.0, 1.0.0]")

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }


    private void setupVersionAlignmentTest() {
        // Simulate a real scenario: external repo has older versions and a dependency that uses them
        File externalRepo = generateMavenRepo(
                // Old versions of our modules (simulating previously published versions)
                "com.palantir:service-a:1.0.0",
                "com.palantir:service-b:1.0.0",
                // External library that depends on just service-a:1.0.0
                // This is the problematic dependency that would cause version skew
                "com.external:some-library:1.0.0 -> com.palantir:service-a:1.0.0"
        )

        //language=gradle
        buildFile << """
            allprojects {
                group = 'com.palantir'
                version = '2.0.0'
                
                repositories {
                    maven { url "file:///${externalRepo.getAbsolutePath()}" }
                }
            }
        """

        def localRepo = new File(projectDir, 'local-repo')
        //language=gradle
        def producerBuildGradle = """
            apply plugin: 'java'
            apply plugin: 'maven-publish'
                    
            publishing {
                repositories {
                    maven {
                        url "file:///${localRepo.absolutePath}"
                    }
                }
                publications {
                    maven(MavenPublication) {
                        from components.java
                    }
                }
            }
        """

        addSubproject('service-a', producerBuildGradle)
        addSubproject('service-b', producerBuildGradle)

        // Consumer subproject that will test the published artifacts
        //language=gradle
        addSubproject('consumer-test', """
            apply plugin: 'java'
            
            // Create a configuration that will only use repository artifacts as we are testing within the same project
            configurations {
                testAlignment {
                    canBeResolved = true
                    canBeConsumed = false
                }
            }
            
            repositories {
                // Repository with our published modules (will be populated after publish)
                maven { 
                    url "file:///${localRepo.absolutePath}"
                    content {
                        includeGroup 'com.palantir'
                    }
                }
                // Repository with external dependencies
                maven { 
                    url "file:///${externalRepo.getAbsolutePath()}"
                }
            }
            
            dependencies {
                testAlignment 'com.palantir:service-b:2.0.0'
                testAlignment 'com.external:some-library:1.0.0'
            }
            
            tasks.register('checkVersions') {
                dependsOn ':service-a:publish', ':service-b:publish'
                doLast {
                    def resolved = [:]
                    configurations.testAlignment.resolvedConfiguration.resolvedArtifacts.each { 
                        if (it.moduleVersion.id.group == 'com.palantir') {
                            resolved[it.moduleVersion.id.module] = it.moduleVersion.id.version
                        }
                    }
                    
                    println "Resolved versions:"
                    resolved.each { k, v -> println "  \${k}: \${v}" }
                    
                    def versions = resolved.values() as Set
                    assert versions.size() == 1 : "Modules should be aligned! Got: \${versions}"
                    assert versions.first() == '2.0.0' : "Should align to latest version, got: \${versions.first()}"
                    println "SUCCESS: Both modules aligned to version 2.0.0"
                }
            }
        """)

        runTasks('--write-locks')
        runTasks(':service-a:publish', ':service-b:publish')
    }
}
