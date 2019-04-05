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

import groovy.util.slurpersupport.GPathResult
import groovy.util.slurpersupport.NodeChildren

class VersionsPropsPluginIntegrationSpec extends IntegrationSpec {

    static def PLUGIN_NAME = "com.palantir.versions-props"

    void setup() {
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
        )
        buildFile << """
            plugins {
                id '${PLUGIN_NAME}'
                id 'com.palantir.configuration-resolver' version '0.3.0' 
            }
            allprojects {
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

    def 'star dependency constraint is injected for direct dependency'() {
        setup:
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
    }

    def 'star dependency constraint is not forcefully downgraded for transitive dependency'() {
        setup:
        file("versions.props") << """
            org.slf4j:* = 1.7.21
            ch.qos.logback:logback-classic = 1.1.11  # brings in slf4j-api 1.7.22
        """.stripIndent()

        addSubproject('foo', """
            apply plugin: 'java'
            dependencies {
                compile 'ch.qos.logback:logback-classic'
            }
        """.stripIndent())

        expect:
        runTasks('resolveConfigurations', '--write-locks')

        file("foo/gradle/dependency-locks/runtimeClasspath.lockfile").text
                .contains("org.slf4j:slf4j-api:1.7.22")
    }

    def 'star dependency constraint upgrades transitive dependency'() {
        setup:
        file("versions.props") << """
            org.slf4j:* = 1.7.25
            ch.qos.logback:logback-classic = 1.1.11  # brings in slf4j-api 1.7.22
        """.stripIndent()

        addSubproject('foo', """
            apply plugin: 'java'
            dependencies {
                compile 'ch.qos.logback:logback-classic'
            }
        """.stripIndent())

        expect:
        runTasks('resolveConfigurations', '--write-locks')

        file("foo/gradle/dependency-locks/runtimeClasspath.lockfile").text
                .contains("org.slf4j:slf4j-api:1.7.25")
    }

    def 'imported platform generated correctly in pom'() {
        debug = true
        keepFiles = true
        file("versions.props") << """
            org.apache.spark:spark-dist_2.11-hadoop-palantir-bom = 2.5.0-palantir.7
            # Just to make sure this doesn't get removed from the constraints
            other:constraint = 1.0.0
        """.stripIndent()

        addSubproject('foo', """
            apply plugin: 'java-library'
            apply plugin: 'maven-publish'
            dependencies {
                compile platform('org.apache.spark:spark-dist_2.11-hadoop-palantir-bom')
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
                        groupId: 'org.apache.spark',
                        artifactId: 'spark-dist_2.11-hadoop-palantir-bom',
                        version: '2.5.0-palantir.7',
                        scope: 'import',
                        type: 'pom'
                ],
                [
                        groupId: 'other',
                        artifactId: 'constraint',
                        version: '1.0.0',
                        scope: 'compile',
                ]
        ] as Set
    }

    def 'non-glob module forces do not get added to a matching platform too'() {
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
    }

    def "throws if resolving configuration in afterEvaluate"() {
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
    }

    def "creates rootConfiguration even if versions props file missing"() {
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
