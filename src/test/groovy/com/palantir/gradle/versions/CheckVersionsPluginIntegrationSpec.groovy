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
import static com.palantir.gradle.versions.PomUtils.makePlatformPom

import spock.lang.Unroll

@Unroll
class CheckVersionsPluginIntegrationSpec extends IntegrationSpec {

    static def PLUGIN_NAME = "com.palantir.check-versions"

    void setup() {
        File mavenRepo = generateMavenRepo(
                "ch.qos.logback:logback-classic:1.2.3 -> org.slf4j:slf4j-api:1.7.25",
                "org.slf4j:slf4j-api:1.7.11",
                "org.slf4j:slf4j-api:1.7.20",
                "org.slf4j:slf4j-api:1.7.24",
                "org.slf4j:slf4j-api:1.7.25",
                "junit:junit:4.10",
                "org:test-dep-that-logs:1.0 -> org.slf4j:slf4j-api:1.7.11"
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
                id 'com.palantir.versions-lock'
                id 'com.palantir.get-versions'
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

    private def standardSetup() {
        buildFile << """
            allprojects {
                // using nebula in ':baz'
                apply plugin: 'nebula.dependency-recommender'
                apply plugin: 'com.palantir.get-versions'
            }
        """.stripIndent()

         addSubproject('foo', '''
            apply plugin: 'java'
            dependencies {
                implementation 'org.slf4j:slf4j-api:1.7.24'
            }
        '''.stripIndent())

        addSubproject('bar', '''
            apply plugin: 'java'
            dependencies {
                implementation "org.slf4j:slf4j-api:${project.bar_version}"
            }
        '''.stripIndent())
        file("gradle.properties") << "bar_version=1.7.11"

        addSubproject('baz', '''
            apply plugin: 'java'
            dependencies {
                implementation "org.slf4j:slf4j-api"
            }
            dependencyRecommendations {
                map recommendations: ['org.slf4j:slf4j-api': '1.7.20']
            }
        '''.stripIndent())

        addSubproject('forced', '''
            apply plugin: 'java'
            dependencies {
                implementation "org.slf4j:slf4j-api"
            }
            configurations.all {
                resolutionStrategy {
                    force "org.slf4j:slf4j-api:1.7.20"
                }
            }
        '''.stripIndent())

        file('versions.props') << 'org.slf4j:slf4j-api = 1.7.24'
    }

    def '#gradleVersionNumber: checkNewVersions discovers and prints new versions'() {
        setup:
        gradleVersion = gradleVersionNumber
        standardSetup()

        expect:
        def result = runTasks('checkNewVersions', '--write-locks')
        // TODO(markelliot): validate output
        println result.output

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

}
