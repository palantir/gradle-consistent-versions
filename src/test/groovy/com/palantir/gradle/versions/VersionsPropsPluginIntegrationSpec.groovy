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

import static com.palantir.gradle.versions.GradleTestVersions.GRADLE_VERSIONS

import groovy.util.slurpersupport.GPathResult
import groovy.util.slurpersupport.NodeChildren
import spock.lang.Unroll

@Unroll
class VersionsPropsPluginIntegrationSpec extends IntegrationSpec {
    static def PLUGIN_NAME = "com.palantir.versions-props"

    void setup() {
        System.setProperty("ignoreDeprecations", "true")

        File mavenRepo = generateMavenRepo(
                "ch.qos.logback:logback-classic:1.2.3 -> org.slf4j:slf4j-api:1.7.25",
                "ch.qos.logback:logback-classic:1.1.11 -> org.slf4j:slf4j-api:1.7.22",
                "org.slf4j:slf4j-api:1.7.21",
                "org.slf4j:slf4j-api:1.7.22",
                "org.slf4j:slf4j-api:1.7.24",
                "org.slf4j:slf4j-api:1.7.25",
                "com.fasterxml.jackson.core:jackson-databind:2.9.0 -> com.fasterxml.jackson.core:jackson-annotations:2.9.0",
                "com.fasterxml.jackson.core:jackson-annotations:2.9.0",
                "com.fasterxml.jackson.core:jackson-annotations:2.9.7",
                "com.fasterxml.jackson.core:jackson-databind:2.9.7",
                "org:platform:1.0",
        )
        buildFile << """
            buildscript {
                repositories {
                    maven { url 'https://dl.bintray.com/palantir/releases' }
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

            // Make it easy to verify what versions of dependencies you got.
            allprojects {
                apply plugin: 'com.palantir.configuration-resolver'
                configurations.matching { it.name == 'runtimeClasspath' }.all {
                    resolutionStrategy.activateDependencyLocking()
                }
            }
        """.stripIndent()
    }

    def '#gradleVersionNumber: star dependency constraint is injected for direct dependency'() {
        setup:
        gradleVersion = gradleVersionNumber

        file("versions.props") << """
            org.slf4j:* = 1.7.24
        """.stripIndent()

        addSubproject('foo', """
            apply plugin: 'java'
            dependencies {
                compile 'org.slf4j:slf4j-api'
            }
        """.stripIndent())

        expect:
        runTasks('resolveConfigurations', '--write-locks')

        file("foo/gradle/dependency-locks/runtimeClasspath.lockfile").text
                .contains("org.slf4j:slf4j-api:1.7.24")

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    def '#gradleVersionNumber: star dependency constraint is not forcefully downgraded for transitive dependency'() {
        setup:
        gradleVersion = gradleVersionNumber

        file("versions.props") << """
            org.slf4j:* = 1.7.21
            ch.qos.logback:logback-classic = 1.1.11  # brings in slf4j-api 1.7.22
        """.stripIndent()

        addSubproject('foo', """
            apply plugin: 'java'
            dependencies {
                implementation 'ch.qos.logback:logback-classic'
            }
        """.stripIndent())

        expect:
        runTasks('resolveConfigurations', '--write-locks')

        file("foo/gradle/dependency-locks/runtimeClasspath.lockfile").text
                .contains("org.slf4j:slf4j-api:1.7.22")

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    def '#gradleVersionNumber: star dependency constraint upgrades transitive dependency'() {
        setup:
        gradleVersion = gradleVersionNumber

        file("versions.props") << """
            org.slf4j:* = 1.7.25
            ch.qos.logback:logback-classic = 1.1.11  # brings in slf4j-api 1.7.22
        """.stripIndent()

        addSubproject('foo', """
            apply plugin: 'java'
            dependencies {
                implementation 'ch.qos.logback:logback-classic'
            }
        """.stripIndent())

        expect:
        runTasks('resolveConfigurations', '--write-locks')

        file("foo/gradle/dependency-locks/runtimeClasspath.lockfile").text
                .contains("org.slf4j:slf4j-api:1.7.25")

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    def '#gradleVersionNumber: imported platform generated correctly in pom'() {
        setup:
        gradleVersion = gradleVersionNumber

        file("versions.props") << """
            org:platform = 1.0
            # This shouldn't end up in the POM
            other:constraint = 1.0.0
        """.stripIndent()

        addSubproject('foo', """
            apply plugin: 'java-library'
            apply plugin: 'maven-publish'
            dependencies {
                rootConfiguration platform('org:platform')
            }
            publishing {
                publications {
                    main(MavenPublication) {
                        from components.java
                    }
                }
            }
        """.stripIndent())

        expect:
        runTasks('foo:generatePomFile')

        file('foo/build/publications/main/pom-default.xml').exists()

        def slurper = new XmlSlurper()
        def pom = slurper.parse(file('foo/build/publications/main/pom-default.xml'))
        NodeChildren dependencies = pom.dependencyManagement.dependencies.dependency
        dependencies.collect { convertToMap(it) } as Set == [
                [
                        groupId: 'org',
                        artifactId: 'platform',
                        version: '1.0',
                        scope: 'import',
                        type: 'pom'
                ],
        ] as Set

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    def '#gradleVersionNumber: non-glob module forces do not get added to a matching platform too'() {
        setup:
        gradleVersion = gradleVersionNumber

        buildFile << '''
            apply plugin: 'java'
            dependencies {
                implementation 'com.fasterxml.jackson.core:jackson-databind'
            }
        '''.stripIndent()
        file('versions.props') << '''
            com.fasterxml.jackson.core:jackson-databind = 2.9.0
            com.fasterxml.jackson.*:* = 2.9.7
        '''.stripIndent()

        when:
        runTasks('resolveConfigurations', '--write-locks')

        then:
        def lockLines = file("gradle/dependency-locks/runtimeClasspath.lockfile").readLines() as Set
        [
                'com.fasterxml.jackson.core:jackson-databind:2.9.0',
                'com.fasterxml.jackson.core:jackson-annotations:2.9.7',
        ].each { lockLines.contains(it) }

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    def "#gradleVersionNumber: throws if resolving configuration in afterEvaluate"() {
        setup:
        gradleVersion = gradleVersionNumber

        buildFile << '''
            configurations { foo }
            
            afterEvaluate {
                configurations.foo.resolve()
            }
        '''
        file('versions.props') << ''

        expect:
        def e = runTasksAndFail()
        e.output.contains("Not allowed to resolve")

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    def "#gradleVersionNumber: does not throw if excluded configuration is resolved early"() {
        setup:
        gradleVersion = gradleVersionNumber

        buildFile << '''
            configurations { foo }
            
            versionRecommendations {
                excludeConfigurations 'foo'
            }
            
            afterEvaluate {
                configurations.foo.resolve()
            }
        '''
        file('versions.props') << ''

        expect:
        runTasks()

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    def "#gradleVersionNumber: creates rootConfiguration even if versions props file missing"() {
        setup:
        gradleVersion = gradleVersionNumber

        buildFile << """
            dependencies {
                constraints {
                    rootConfiguration 'org.slf4j:slf4j-api:1.7.25'
                }
            }
        """.stripIndent()
        file('versions.props').delete()

        expect:
        runTasks()

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    /**
     * Recursively converts a node's children to a map of <tt>(tag name): (value inside tag)</tt>.
     * <p>
     * See: https://stackoverflow.com/a/26889997/274699
     */
    def convertToMap(GPathResult node) {
        node.children().collectEntries {
            [ it.name(), it.childNodes() ? convertToMap(it) : it.text() ]
        }
    }
}
