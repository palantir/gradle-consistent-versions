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

    def "#gradleVersionNumber: published constraints with local constraints only"() {
        setup:
        // Test with local constraints enabled
        file('gradle.properties') << 'com.palantir.gradle.versions.publishLocalConstraints = true'
        gradleVersion = gradleVersionNumber

        buildFile << """
            allprojects {
                apply plugin: 'java'
            }
        """.stripIndent(true)

        String publish = """
            apply plugin: 'maven-publish'
            group = 'com.palantir.published-constraints'
            version = '1.2.3'
            publishing.publications {
                maven(MavenPublication) {
                    from components.java
                }
            }
        """.stripIndent(true)

        addSubproject('foo', """
            $publish
            dependencies {
                implementation 'ch.qos.logback:logback-classic:1.2.3'
            }
        """.stripIndent(true))

        addSubproject('bar', """
            $publish
            dependencies {
                implementation 'junit:junit:4.10'
            }
        """.stripIndent(true))

        if (GradleVersion.version(gradleVersionNumber) < GradleVersion.version("6.0")) {
            settingsFile << """
                enableFeaturePreview('GRADLE_METADATA')
            """.stripIndent(true)
        }

        runTasks('--write-locks')

        when:
        runTasks('generatePomFileForMavenPublication', 'generateMetadataFileForMavenPublication')

        def junitDep = new MetadataFile.Dependency(
                group: 'junit',
                module: 'junit',
                version: [requires: '4.10'])
        def logbackDep = new MetadataFile.Dependency(
                group: 'ch.qos.logback',
                module: 'logback-classic',
                version: [requires: '1.2.3'])
        def fooDep = new MetadataFile.Dependency(
                group: 'com.palantir.published-constraints',
                module: 'foo',
                version: [requires: '1.2.3'])
        def barDep = new MetadataFile.Dependency(
                group: 'com.palantir.published-constraints',
                module: 'bar',
                version: [requires: '1.2.3'])
        def slf4jDep = new MetadataFile.Dependency(
                group: 'org.slf4j',
                module: 'slf4j-api',
                version: [requires: '1.7.25'])

        then: "foo's metadata file has the right dependency constraints"
        def fooMetadataFilename = new File(projectDir, "foo/build/publications/maven/module.json")
        def fooMetadata = new ObjectMapper().readValue(fooMetadataFilename, MetadataFile)

        fooMetadata.variants == [
                new MetadataFile.Variant(
                        name: 'runtimeElements',
                        dependencies: [logbackDep],
                        dependencyConstraints: [barDep, logbackDep, slf4jDep]),
                new MetadataFile.Variant(
                        name: 'apiElements',
                        dependencies: null,
                        dependencyConstraints: [barDep, logbackDep, slf4jDep])
        ] as Set

        and: "bar's metadata file has the right dependency constraints"
        def barMetadataFilename = new File(projectDir, "bar/build/publications/maven/module.json")
        def barMetadata = new ObjectMapper().readValue(barMetadataFilename, MetadataFile)

        barMetadata.variants == [
                new MetadataFile.Variant(
                        name: 'runtimeElements',
                        dependencies: [junitDep],
                        dependencyConstraints: [fooDep, junitDep]),
                new MetadataFile.Variant(
                        name: 'apiElements',
                        dependencies: null,
                        dependencyConstraints: [fooDep, junitDep]),
        ] as Set

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    def "#gradleVersionNumber: published constraints with platform constraints only"() {
        setup:
        // Test with platform constraints enabled
        file('gradle.properties') << 'com.palantir.gradle.versions.publishPlatformConstraints = true'
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
            dependencies {
                implementation 'ch.qos.logback:logback-classic:1.2.3'
            }
        """.stripIndent(true))

        addSubproject('service-b', """
            $publish
            dependencies {
                implementation 'junit:junit:4.10'
            }
        """.stripIndent(true))

        if (GradleVersion.version(gradleVersionNumber) < GradleVersion.version("6.0")) {
            settingsFile << """
                enableFeaturePreview('GRADLE_METADATA')
            """.stripIndent(true)
        }

        runTasks('--write-locks')

        when:
        runTasks('generatePomFileForMavenPublication', 'generateMetadataFileForMavenPublication')

        def junitDep = new MetadataFile.Dependency(
                group: 'junit',
                module: 'junit',
                version: [requires: '4.10'])
        def logbackDep = new MetadataFile.Dependency(
                group: 'ch.qos.logback',
                module: 'logback-classic',
                version: [requires: '1.2.3'])
        def slf4jDep = new MetadataFile.Dependency(
                group: 'org.slf4j',
                module: 'slf4j-api',
                version: [requires: '1.7.25'])
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
                        dependencies: [logbackDep],
                        dependencyConstraints: [serviceBConstraint, logbackDep, slf4jDep] as Set),
                new MetadataFile.Variant(
                        name: 'apiElements',
                        dependencies: null,
                        dependencyConstraints: [serviceBConstraint, logbackDep, slf4jDep] as Set)
        ] as Set

        and: "service-b's metadata file has platform constraints and filtered lock file constraints"
        def serviceBMetadataFilename = new File(projectDir, "service-b/build/publications/maven/module.json")
        def serviceBMetadata = new ObjectMapper().readValue(serviceBMetadataFilename, MetadataFile)

        serviceBMetadata.variants == [
                new MetadataFile.Variant(
                        name: 'runtimeElements',
                        dependencies: [junitDep],
                        dependencyConstraints: [serviceAConstraint, junitDep] as Set),
                new MetadataFile.Variant(
                        name: 'apiElements',
                        dependencies: null,
                        dependencyConstraints: [serviceAConstraint, junitDep] as Set),
        ] as Set

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    def "#gradleVersionNumber: published constraints with both platform and local constraints"() {
        setup:
        // Test with both constraints enabled
        file('gradle.properties') << '''
            com.palantir.gradle.versions.publishPlatformConstraints = true
            com.palantir.gradle.versions.publishLocalConstraints = true
        '''.stripIndent()
        gradleVersion = gradleVersionNumber

        buildFile << """
            allprojects {
                apply plugin: 'java'
            }
        """.stripIndent(true)

        String publish = """
            apply plugin: 'maven-publish'
            group = 'com.palantir.combined'
            version = '3.0.0'
            publishing.publications {
                maven(MavenPublication) {
                    from components.java
                }
            }
        """.stripIndent(true)

        addSubproject('api', """
            $publish
            dependencies {
                implementation 'ch.qos.logback:logback-classic:1.2.3'
            }
        """.stripIndent(true))

        addSubproject('impl', """
            $publish
            dependencies {
                implementation 'junit:junit:4.10'
            }
        """.stripIndent(true))

        addSubproject('util', """
            $publish
            dependencies {
                implementation 'org.slf4j:slf4j-api:1.7.25'
            }
        """.stripIndent(true))

        if (GradleVersion.version(gradleVersionNumber) < GradleVersion.version("6.0")) {
            settingsFile << """
                enableFeaturePreview('GRADLE_METADATA')
            """.stripIndent(true)
        }

        runTasks('--write-locks')

        when:
        runTasks('generatePomFileForMavenPublication', 'generateMetadataFileForMavenPublication')

        def junitDep = new MetadataFile.Dependency(
                group: 'junit',
                module: 'junit',
                version: [requires: '4.10'])
        def logbackDep = new MetadataFile.Dependency(
                group: 'ch.qos.logback',
                module: 'logback-classic',
                version: [requires: '1.2.3'])
        def slf4jDep = new MetadataFile.Dependency(
                group: 'org.slf4j',
                module: 'slf4j-api',
                version: [requires: '1.7.25'])

        // Platform constraints (same version)
        def apiPlatformConstraint = new MetadataFile.Dependency(
                group: 'com.palantir.combined',
                module: 'api',
                version: [requires: '[3.0.0,)'])
        def implPlatformConstraint = new MetadataFile.Dependency(
                group: 'com.palantir.combined',
                module: 'impl',
                version: [requires: '[3.0.0,)'])
        def utilPlatformConstraint = new MetadataFile.Dependency(
                group: 'com.palantir.combined',
                module: 'util',
                version: [requires: '[3.0.0,)'])

        // Local constraints (exact version)
        def apiLocalConstraint = new MetadataFile.Dependency(
                group: 'com.palantir.combined',
                module: 'api',
                version: [requires: '3.0.0'])
        def implLocalConstraint = new MetadataFile.Dependency(
                group: 'com.palantir.combined',
                module: 'impl',
                version: [requires: '3.0.0'])
        def utilLocalConstraint = new MetadataFile.Dependency(
                group: 'com.palantir.combined',
                module: 'util',
                version: [requires: '3.0.0'])

        then: "api's metadata file has both platform and local constraints plus filtered lock constraints"
        def apiMetadataFilename = new File(projectDir, "api/build/publications/maven/module.json")
        def apiMetadata = new ObjectMapper().readValue(apiMetadataFilename, MetadataFile)

        apiMetadata.variants == [
                new MetadataFile.Variant(
                        name: 'runtimeElements',
                        dependencies: [logbackDep],
                        dependencyConstraints: [implPlatformConstraint, utilPlatformConstraint, implLocalConstraint, utilLocalConstraint, logbackDep, slf4jDep] as Set),
                new MetadataFile.Variant(
                        name: 'apiElements',
                        dependencies: null,
                        dependencyConstraints: [implPlatformConstraint, utilPlatformConstraint, implLocalConstraint, utilLocalConstraint, logbackDep, slf4jDep] as Set)
        ] as Set

        and: "impl's metadata file has both platform and local constraints plus filtered lock constraints"
        def implMetadataFilename = new File(projectDir, "impl/build/publications/maven/module.json")
        def implMetadata = new ObjectMapper().readValue(implMetadataFilename, MetadataFile)

        implMetadata.variants == [
                new MetadataFile.Variant(
                        name: 'runtimeElements',
                        dependencies: [junitDep],
                        dependencyConstraints: [apiPlatformConstraint, utilPlatformConstraint, apiLocalConstraint, utilLocalConstraint, junitDep] as Set),
                new MetadataFile.Variant(
                        name: 'apiElements',
                        dependencies: null,
                        dependencyConstraints: [apiPlatformConstraint, utilPlatformConstraint, apiLocalConstraint, utilLocalConstraint, junitDep] as Set),
        ] as Set

        and: "util's metadata file has both platform and local constraints plus filtered lock constraints"
        def utilMetadataFilename = new File(projectDir, "util/build/publications/maven/module.json")
        def utilMetadata = new ObjectMapper().readValue(utilMetadataFilename, MetadataFile)

        utilMetadata.variants == [
                new MetadataFile.Variant(
                        name: 'runtimeElements',
                        dependencies: [slf4jDep],
                        dependencyConstraints: [apiPlatformConstraint, implPlatformConstraint, apiLocalConstraint, implLocalConstraint, slf4jDep] as Set),
                new MetadataFile.Variant(
                        name: 'apiElements',
                        dependencies: null,
                        dependencyConstraints: [apiPlatformConstraint, implPlatformConstraint, apiLocalConstraint, implLocalConstraint, slf4jDep] as Set),
        ] as Set

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    def "#gradleVersionNumber: published constraints without any constraints enabled (but with filtered lock constraints)"() {
        setup:
        gradleVersion = gradleVersionNumber

        buildFile << """
            allprojects {
                apply plugin: 'java'
                apply plugin: 'maven-publish'
                publishing.publications {
                    maven(MavenPublication) {
                        from components.java
                    }
                }
            }
        """.stripIndent(true)

        addSubproject('foo', """
            dependencies {
                implementation 'ch.qos.logback:logback-classic:1.2.3'
            }
        """.stripIndent(true))

        addSubproject('bar', """
            dependencies {
                implementation 'junit:junit:4.10'
            }
        """.stripIndent(true))

        if (GradleVersion.version(gradleVersionNumber) < GradleVersion.version("6.0")) {
            settingsFile << """
                enableFeaturePreview('GRADLE_METADATA')
            """.stripIndent(true)
        }

        runTasks('--write-locks')

        when:
        runTasks('generatePomFileForMavenPublication', 'generateMetadataFileForMavenPublication')

        def junitDep = new MetadataFile.Dependency(
                group: 'junit',
                module: 'junit',
                version: [requires: '4.10'])
        def logbackDep = new MetadataFile.Dependency(
                group: 'ch.qos.logback',
                module: 'logback-classic',
                version: [requires: '1.2.3'])
        def slf4jDep = new MetadataFile.Dependency(
                group: 'org.slf4j',
                module: 'slf4j-api',
                version: [requires: '1.7.25'])

        then: "foo's metadata file has filtered lock file constraints only"
        def fooMetadataFilename = new File(projectDir, "foo/build/publications/maven/module.json")
        def fooMetadata = new ObjectMapper().readValue(fooMetadataFilename, MetadataFile)

        fooMetadata.variants == [
                new MetadataFile.Variant(
                        name: 'apiElements',
                        dependencies: null,
                        dependencyConstraints: [logbackDep, slf4jDep] as Set),
                new MetadataFile.Variant(
                        name: 'runtimeElements',
                        dependencies: [logbackDep],
                        dependencyConstraints: [logbackDep, slf4jDep] as Set),
        ] as Set

        and: "bar's metadata file has filtered lock file constraints only"
        def barMetadataFilename = new File(projectDir, "bar/build/publications/maven/module.json")
        def barMetadata = new ObjectMapper().readValue(barMetadataFilename, MetadataFile)

        barMetadata.variants == [
                new MetadataFile.Variant(
                        name: 'apiElements',
                        dependencies: null,
                        dependencyConstraints: [junitDep] as Set),
                new MetadataFile.Variant(
                        name: 'runtimeElements',
                        dependencies: [junitDep],
                        dependencyConstraints: [junitDep] as Set),
        ] as Set

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }
}
