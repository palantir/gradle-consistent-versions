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
import org.gradle.testkit.runner.TaskOutcome
import org.gradle.util.GradleVersion
import spock.lang.Unroll

import static com.palantir.gradle.versions.GradleTestVersions.GRADLE_VERSIONS
import static com.palantir.gradle.versions.PomUtils.makePlatformPom

@Unroll
class GradleModuleMetadataConstraintsPluginIntegrationSpec extends IntegrationSpec {

    static def PLUGIN_NAME = "com.palantir.gradle-module-metadata-constraints-plugin"

    void setup() {
        File mavenRepo = generateMavenRepo(
                "ch.qos.logback:logback-classic:1.2.3 -> org.slf4j:slf4j-api:1.7.25",
                "org.slf4j:slf4j-api:1.7.11",
                "org.slf4j:slf4j-api:1.7.20",
                "org.slf4j:slf4j-api:1.7.24",
                "org.slf4j:slf4j-api:1.7.25",
                "junit:junit:4.10",
                "org:test-dep-that-logs:1.0 -> org.slf4j:slf4j-api:1.7.11",
                "org:another-transitive-dependency:3.2.1",
                "org:another-direct-dependency:1.2.3 -> org:another-transitive-dependency:3.2.1",
        )
        makePlatformPom(mavenRepo, "org", "platform", "1.0")

        buildFile << """
            buildscript {
                repositories {
                    mavenCentral()
                }
            }
            plugins {
                id '${PLUGIN_NAME}'
            }
            allprojects {
                repositories {
                    maven { url "file:///${mavenRepo.getAbsolutePath()}" }
                }
                
                task resolveConfigurations {
                    doLast {
                        if (pluginManager.hasPlugin('java')) {
                            configurations.compileClasspath.resolve()
                            configurations.runtimeClasspath.resolve()
                        }
                    }
                }
            }
        """
    }

    def "#gradleVersionNumber: published constraints with platform constraints only"() {
        setup:
        // Test with platform constraints enabled
        file('gradle.properties') << '''
            com.palantir.gradle.versions.publishPlatformConstraints = true
        '''
        gradleVersion = gradleVersionNumber

        buildFile << """
            allprojects {
                apply plugin: 'java'
            }
        """.stripIndent(true)

        String publish = """
            apply plugin: 'maven-publish'
            group = 'com.palantir.same-group'
            version = '2.0.0'
            publishing.publications {
                maven(MavenPublication) {
                    from components.java
                }
            }
        """.stripIndent(true)

        addSubproject('service-a', """
            $publish
        """.stripIndent(true))

        addSubproject('service-b', """
            $publish
        """.stripIndent(true))

        if (GradleVersion.version(gradleVersionNumber) < GradleVersion.version("6.0")) {
            settingsFile << """
                enableFeaturePreview('GRADLE_METADATA')
            """.stripIndent(true)
        }

        runTasks('--write-locks')

        when:
        runTasks('generatePomFileForMavenPublication', 'generateMetadataFileForMavenPublication')

        def serviceAConstraint = new MetadataFile.Dependency(
                group: 'com.palantir.same-group',
                module: 'service-a',
                version: [requires: '[2.0.0,)'])
        def serviceBConstraint = new MetadataFile.Dependency(
                group: 'com.palantir.same-group',
                module: 'service-b',
                version: [requires: '[2.0.0,)'])

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
}
