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
import org.gradle.util.GradleVersion
import spock.lang.Unroll

import static com.palantir.gradle.versions.GradleTestVersions.GRADLE_VERSIONS
import static com.palantir.gradle.versions.PomUtils.makePlatformPom

@Unroll
class ConsistentVersionsPluginIntegrationSpec extends IntegrationSpec {

    static def PLUGIN_NAME = "com.palantir.consistent-versions"
    private File mavenRepo

    void setup() {
        mavenRepo = generateMavenRepo(
                "ch.qos.logback:logback-classic:1.1.11 -> org.slf4j:slf4j-api:1.7.22",
                "org.slf4j:slf4j-api:1.7.22",
                "org.slf4j:slf4j-api:1.7.25",
                "test-alignment:module-that-should-be-aligned-up:1.0",
                "test-alignment:module-that-should-be-aligned-up:1.1",
                "test-alignment:module-with-higher-version:1.1")

        makePlatformPom(mavenRepo, "org", "platform", "1.0")

        buildFile << """
            buildscript {
                repositories {
                    mavenCentral()
                }
                dependencies {
                    classpath 'com.palantir.configurationresolver:gradle-configuration-resolver-plugin:0.3.0'
                }
            }
            plugins {
                id '${PLUGIN_NAME}'
            }
            allprojects {
                apply plugin: 'com.palantir.configuration-resolver'
                
                repositories {
                    maven { url "file:///${mavenRepo.getAbsolutePath()}" }
                }
            }
        """.stripIndent()
    }

    def '#gradleVersionNumber: can write locks using --write-locks'() {
        setup:
        gradleVersion = gradleVersionNumber

        when:
        runTasks('--write-locks')

        then:
        new File(projectDir, "versions.lock").exists()
        runTasks('resolveConfigurations')

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    def '#gradleVersionNumber: can write locks using writeVersionsLock'() {
        setup:
        gradleVersion = gradleVersionNumber

        when:
        runTasks('writeVersionsLock')

        then:
        new File(projectDir, "versions.lock").exists()
        runTasks('resolveConfigurations')

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    def '#gradleVersionNumber: can write locks using abbreivated writeVersionsLock'() {
        setup:
        gradleVersion = gradleVersionNumber

        when:
        runTasks('wVL')

        then:
        new File(projectDir, "versions.lock").exists()
        runTasks('resolveConfigurations')

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    // TODO(dsanduleac): should remove this since this functionality doesn't fully work anyway, and we are
    //   actively encouraging people to stop resolving the deprecated configurations `compile` and `runtime`.
    def '#gradleVersionNumber: can resolve all configurations like compile with version coming only from versions props'() {
        setup:
        gradleVersion = gradleVersionNumber

        file('versions.props') << """
            org.slf4j:slf4j-api = 1.7.22
        """.stripIndent()

        buildFile << """
            apply plugin: 'java'
            dependencies {
                implementation "org.slf4j:slf4j-api"
            }
        """.stripIndent()

        when:
        runTasks('--write-locks')

        then:
        // Ensures that configurations like 'compile' are resolved and their dependencies have versions
        runTasks('--warning-mode=none', 'resolveConfigurations')

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    def "#gradleVersionNumber: locks are consistent whether or not we do --write-locks for glob-forced direct dependency"() {
        setup:
        gradleVersion = gradleVersionNumber

        buildFile << '''
            apply plugin: 'java'
            dependencies {
                implementation 'org.slf4j:slf4j-api'
                runtimeOnly 'ch.qos.logback:logback-classic:1.1.11' // brings in slf4j-api 1.7.22
            }
            
            task resolve { doLast { configurations.runtimeClasspath.resolve() } }
        '''.stripIndent()

        file('versions.props') << 'org.slf4j:* = 1.7.25'

        expect:
        runTasks('resolve', '--write-locks')
        runTasks('resolve')

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    def "#gradleVersionNumber: getVersion function works"() {
        setup:
        gradleVersion = gradleVersionNumber

        buildFile << '''
            apply plugin: 'java'
            dependencies {
                implementation 'org.slf4j:slf4j-api'
            }

            task demo {
                doLast { println "demo=" + getVersion('org.slf4j:slf4j-api', configurations.compileClasspath) }
            }
        '''.stripIndent()

        // Pretend we have a lock file
        file('versions.lock') << ''

        file('versions.props') << 'org.slf4j:* = 1.7.25'

        expect:
        runTasks('demo').output.contains("demo=1.7.25")

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    def "#gradleVersionNumber: getVersion function works even when writing locks"() {
        setup:
        gradleVersion = gradleVersionNumber

        buildFile << '''
            apply plugin: 'java'
            dependencies {
                implementation 'org.slf4j:slf4j-api'
            }

            task demo {
                doLast { println "demo=" + getVersion('org.slf4j:slf4j-api') }
            }
        '''.stripIndent()

        file('versions.props') << 'org.slf4j:* = 1.7.25'

        expect:
        runTasks('demo', '--write-locks').output.contains("demo=1.7.25")

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    def "#gradleVersionNumber: virtual platform is respected across projects"() {
        setup:
        gradleVersion = gradleVersionNumber

        addSubproject('foo', """
            apply plugin: 'java'
            dependencies {
                implementation 'test-alignment:module-that-should-be-aligned-up:1.0'
            }
        """.stripIndent())

        addSubproject('bar', """
            apply plugin: 'java'
            dependencies {
                implementation 'test-alignment:module-with-higher-version:1.1'
            }
        """.stripIndent())

        file('versions.props') << """
            # Just to create a platform around test-alignment:*
            test-alignment:* = 1.0
        """.stripIndent()

        when:
        runTasks('--write-locks')

        then:
        def expectedLock = """\
            # Run ./gradlew --write-locks to regenerate this file
            test-alignment:module-that-should-be-aligned-up:1.1 (1 constraints: a5041a2c)
            test-alignment:module-with-higher-version:1.1 (1 constraints: a6041b2c)
        """.stripIndent()
        file('versions.lock').text == expectedLock

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    def "#gradleVersionNumber: star dependencies in the absence of dependency versions"() {
        setup:
        gradleVersion = gradleVersionNumber

        addSubproject('foo', """
            apply plugin: 'java'
            dependencies {
                implementation 'org.slf4j:slf4j-api'
            }
        """.stripIndent())

        file('versions.props') << """
            org.slf4j:* = 1.7.25
        """.stripIndent()

        when:
        runTasks('--write-locks')

        then:
        def expectedLock = """\
            # Run ./gradlew --write-locks to regenerate this file
            org.slf4j:slf4j-api:1.7.25 (1 constraints: 4105483b)
        """.stripIndent()
        file('versions.lock').text == expectedLock

        // Ensure that this is a required constraint
        runTasks('why', '--hash', '4105483b').output.contains "projects -> 1.7.25"

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    def "#gradleVersionNumber: writeLocks and verifyLocks work in the presence of versions props constraints"() {
        setup:
        gradleVersion = gradleVersionNumber

        makePlatformPom(mavenRepo, "org1", "platform", "1.0")
        makePlatformPom(mavenRepo, "org2", "platform", "1.0")

        addSubproject('foo', """
            apply plugin: 'java'
            
            configurations {
                other
            }
            
            dependencies {
                implementation 'org.slf4j:slf4j-api'
                
                rootConfiguration platform('org1:platform')
                rootConfiguration platform('org2:platform')
            }
  
            task resolveLockedConfigurations {
                doLast {
                    configurations.compileClasspath.resolve()
                    configurations.runtimeClasspath.resolve()
                }
            }
            
            // This is to ensure that the platform deps are successfully resolvable on a non-locked configuration
            task resolveNonLockedConfiguration {
                doLast {
                    configurations.other.resolve()
                }
            }
        """.stripIndent())

        file('versions.props') << """
            org1:platform = 1.0
            org2:* = 1.0
            org.slf4j:slf4j-api = 1.7.25
        """.stripIndent()

        expect:
        runTasks('--write-locks')

        file('versions.lock').text == """\
            # Run ./gradlew --write-locks to regenerate this file
            org.slf4j:slf4j-api:1.7.25 (1 constraints: 4105483b)
            org1:platform:1.0 (1 constraints: a5041a2c)
            org2:platform:1.0 (1 constraints: a5041a2c)
        """.stripIndent()

        and: 'Ensure you can verify locks and resolve the actual locked configurations'
        runTasks('verifyLocks', 'resolveLockedConfigurations', 'resolveNonLockedConfiguration')

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    def "#gradleVersionNumber: versions props contents do not get published as constraints"() {
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
        """.stripIndent()

        addSubproject('foo', """
            apply plugin: 'java'
            dependencies {
                implementation 'ch.qos.logback:logback-classic'
            }
        """.stripIndent())

        file('versions.props') << """
            org.slf4j:* = 1.7.25
            ch.qos.logback:* = 1.1.11
            should:not-publish = 1.0
        """.stripIndent()

        if (GradleVersion.version(gradleVersionNumber) < GradleVersion.version("6.0")) {
            settingsFile << """
                enableFeaturePreview('GRADLE_METADATA')
            """.stripIndent()
        }

        when:
        runTasks('--write-locks', 'generateMetadataFileForMavenPublication')

        def logbackDep = new MetadataFile.Dependency(
                group: 'ch.qos.logback',
                module: 'logback-classic',
                version: [requires: '1.1.11'])
        def slf4jDep = new MetadataFile.Dependency(
                group: 'org.slf4j',
                module: 'slf4j-api',
                version: [requires: '1.7.25'])

        then: "foo's metadata file has the right dependency constraints"
        def fooMetadataFilename = new File(projectDir, "foo/build/publications/maven/module.json")
        def fooMetadata = new ObjectMapper().readValue(fooMetadataFilename, MetadataFile)

        fooMetadata.variants == [
                new MetadataFile.Variant(
                        name: 'apiElements',
                        dependencies: null,
                        dependencyConstraints: [logbackDep, slf4jDep]),
                new MetadataFile.Variant(
                        name: 'runtimeElements',
                        dependencies: [logbackDep],
                        dependencyConstraints: [logbackDep, slf4jDep]),
        ] as Set

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    def "#gradleVersionNumber: intransitive dependency on published configuration should not break realizing it later"() {
        setup:
        gradleVersion = gradleVersionNumber

        addSubproject('source', """
            configurations {
                transitive
                intransitive
            }
            dependencies {
                // This wrecks us
                intransitive project(':target'), { transitive = false }
                
                transitive project(':target')
            }
            
            task resolveIntransitively {
                doLast {
                    configurations.intransitive.resolvedConfiguration
                }
            }
            task resolveTransitively {
                mustRunAfter resolveIntransitively
                doLast {
                    configurations.transitive.resolvedConfiguration
                }
            }
        """.stripIndent())

        addSubproject('target', """
            apply plugin: 'java'
            
            dependencies {
                // Test the lazy action on published configurations like apiElements, runtimeElements
                // that copies over platform dependencies from rootConfiguration.
                rootConfiguration platform("org:platform")
            }            
        """.stripIndent())

        file('versions.props') << """
            org:platform = 1.0
        """.stripIndent()

        // This is just for debugging
        buildFile << """
            allprojects {
                configurations.all {
                    incoming.beforeResolve {
                        println "Resolving: \$it"
                    }
                }
            }
        """.stripIndent()

        runTasks('--write-locks')

        expect:
        runTasks('resolveIntransitively', 'resolveTransitively')

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }
}
