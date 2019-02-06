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

import nebula.test.IntegrationTestKitSpec

class ConsistentVersionsPluginIntegrationSpec extends IntegrationTestKitSpec {

    static def PLUGIN_NAME = "com.palantir.consistent-versions"

    void setup() {
        keepFiles = true
        settingsFile.createNewFile()
        buildFile << """
            buildscript {
                dependencies {
                    classpath 'com.palantir.configurationresolver:gradle-configuration-resolver-plugin:0.3.0'
                }
                repositories {
                    jcenter()
                    maven {
                        url 'https://dl.bintray.com/palantir/releases/'
                    }
                }
            }
            plugins { id '${PLUGIN_NAME}' }
            allprojects {
                apply plugin: 'com.palantir.configuration-resolver'
            }
        """.stripIndent()
    }

    def 'can write locks'() {
        buildFile << '''
            repositories {
                jcenter()
            }
        '''.stripIndent()

        expect:
        runTasks('--write-locks')
        new File(projectDir, "versions.lock").exists()

        runTasks('resolveConfigurations')
    }

    def "locks are consistent whether or not we do --write-locks for glob-forced direct dependency"() {
        buildFile << '''
            repositories { jcenter() }
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
            repositories { jcenter() }
            apply plugin: 'java'
            dependencies {
                compile 'org.slf4j:slf4j-api'
            }

            task demo {
                doLast { println "demo=" + getVersion('org.slf4j:slf4j-api', configurations.compileClasspath) }
            }
        '''.stripIndent()

        file('versions.props') << 'org.slf4j:* = 1.7.25'

        expect:
        runTasks('demo').output.contains("demo=1.7.25")
    }
}
