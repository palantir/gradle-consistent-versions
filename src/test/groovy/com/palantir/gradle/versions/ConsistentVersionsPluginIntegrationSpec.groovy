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

class ConsistentVersionsPluginIntegrationSpec extends IntegrationSpec {

    static def PLUGIN_NAME = "com.palantir.consistent-versions"

    void setup() {
        File mavenRepo = generateMavenRepo(
                "ch.qos.logback:logback-classic:1.1.11 -> org.slf4j:slf4j-api:1.7.22",
                "org.slf4j:slf4j-api:1.7.22",
                "org.slf4j:slf4j-api:1.7.25",
                "test-alignment:module-that-should-be-aligned-up:1.0",
                "test-alignment:module-that-should-be-aligned-up:1.1",
                "test-alignment:module-with-higher-version:1.1",
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
        """.stripIndent()
    }

    def 'can write locks'() {
        when:
        runTasks('--write-locks')

        then:
        new File(projectDir, "versions.lock").exists()
        runTasks('resolveConfigurations')
    }

    def "locks are consistent whether or not we do --write-locks for glob-forced direct dependency"() {
        buildFile << '''
            apply plugin: 'java'
            dependencies {
                compile 'org.slf4j:slf4j-api'
                compile 'ch.qos.logback:logback-classic:1.1.11' // brings in slf4j-api 1.7.22
            }
        '''.stripIndent()

        file('versions.props') << 'org.slf4j:* = 1.7.25'

        expect:
        runTasks('resolveConfigurations', '--write-locks')
        runTasks('resolveConfigurations')
    }

    def "getVersion function works"() {
        buildFile << '''
            apply plugin: 'java'
            dependencies {
                compile 'org.slf4j:slf4j-api'
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
    }

    def "getVersion function works even when writing locks"() {
        buildFile << '''
            apply plugin: 'java'
            dependencies {
                compile 'org.slf4j:slf4j-api'
            }

            task demo {
                doLast { println "demo=" + getVersion('org.slf4j:slf4j-api') }
            }
        '''.stripIndent()

        file('versions.props') << 'org.slf4j:* = 1.7.25'

        expect:
        runTasks('demo', '--write-locks').output.contains("demo=1.7.25")
    }

    def "virtual platform is respected across projects"() {
        addSubproject('foo', """
            apply plugin: 'java'
            dependencies {
                compile 'test-alignment:module-that-should-be-aligned-up:1.0'
            }
        """.stripIndent())

        addSubproject('bar', """
            apply plugin: 'java'
            dependencies {
                compile 'test-alignment:module-with-higher-version:1.1'
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
    }

    def "star dependencies in the absence of dependency versions"() {
        addSubproject('foo', """
            apply plugin: 'java'
            dependencies {
                compile 'org.slf4j:slf4j-api'
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
    }

    def "writeLocks and verifyLocks work in the presence of versions props constraints"() {
        generateMavenRepo(
                "org1:platform:1.0",
                "org2:platform:1.0",
        )
        addSubproject('foo', """
            apply plugin: 'java'
            dependencies {
                compile 'org.slf4j:slf4j-api'
                
                rootConfiguration platform('org1:platform')
                rootConfiguration platform('org2:platform')
            }
            
            task resolveLockedConfigurations {
                doLast {
                    configurations.compileClasspath.resolve()
                    configurations.runtimeClasspath.resolve()
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
        runTasks('verifyLocks', 'resolveLockedConfigurations')
    }
}
