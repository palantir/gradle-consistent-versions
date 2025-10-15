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
import com.palantir.gradle.plugintesting.ConfigurationCacheSpec;
import spock.lang.Unroll;

import static com.palantir.gradle.versions.GradleTestVersions.GRADLE_VERSIONS


@Unroll
class ConstraintProducerPluginIntegrationSpec extends ConfigurationCacheSpec {

    void setup() {
        //language=gradle
        buildFile << """
            import com.palantir.gradle.versions.ConstraintProducerPlugin

            apply plugin: ConstraintProducerPlugin
            
            allprojects {
                group = 'com.palantir'
                version = '1.0.0'
            }
            
            subprojects {
                apply plugin: 'java'
                apply plugin: 'maven-publish'
                
                publishing {
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
    }

    def "check does not break configuration cache"() {
        expect:
        runTasksWithConfigurationCache('build')
    }

    def "#gradleVersionNumber: published constraints with platform constraint"() {
        when:
        runTasks('generatePomFileForMavenPublication', 'generateMetadataFileForMavenPublication')

        def virtualPlatformConstraint = new MetadataFile.Dependency(
                group: 'consistent-versions.external-virtual-platform.com.palantir',
                module: '_',
                version: [requires: '1.0.0'])

        then: "service-a's metadata file has the virtual platform constraint"
        def serviceAMetadataFilename = new File(projectDir, "service-a/build/publications/maven/module.json")
        def serviceAMetadata = new ObjectMapper().readValue(serviceAMetadataFilename, MetadataFile)

        serviceAMetadata.variants == [
                new MetadataFile.Variant(
                        name: 'runtimeElements',
                        dependencies: null,
                        dependencyConstraints: [virtualPlatformConstraint] as Set),
                new MetadataFile.Variant(
                        name: 'apiElements',
                        dependencies: null,
                        dependencyConstraints: [virtualPlatformConstraint] as Set)
        ] as Set

        and: "service-b's metadata file has the virtual platform constraint"
        def serviceBMetadataFilename = new File(projectDir, "service-b/build/publications/maven/module.json")
        def serviceBMetadata = new ObjectMapper().readValue(serviceBMetadataFilename, MetadataFile)

        serviceBMetadata.variants == [
                new MetadataFile.Variant(
                        name: 'runtimeElements',
                        dependencies: null,
                        dependencyConstraints: [virtualPlatformConstraint] as Set),
                new MetadataFile.Variant(
                        name: 'apiElements',
                        dependencies: null,
                        dependencyConstraints: [virtualPlatformConstraint] as Set),
        ] as Set

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }
}
