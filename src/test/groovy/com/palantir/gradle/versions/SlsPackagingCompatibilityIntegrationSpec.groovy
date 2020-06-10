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


import org.gradle.testkit.runner.TaskOutcome

/**
 * https://github.com/palantir/sls-packaging does some funky stuff when resolving inter-project dependencies for the
 * purposes of detecting published recommended product dependencies, so we want to make double sure that GCV doesn't
 * accidentally break it.
 */
class SlsPackagingCompatibilityIntegrationSpec extends IntegrationSpec {

    static def PLUGIN_NAME = "com.palantir.consistent-versions"

    void setup() {
        File mavenRepo = generateMavenRepo(
                "org.slf4j:slf4j-api:1.7.24",
        )
        buildFile << """
            buildscript {
                repositories {
                    maven { url 'https://dl.bintray.com/palantir/releases' }
                }
            }            
            plugins {
                id '${PLUGIN_NAME}'
                id 'com.palantir.sls-java-service-distribution' version '3.8.1' apply false
            }
            allprojects {
                repositories {
                    maven { url "file:///${mavenRepo.getAbsolutePath()}" }
                }
            }
        """.stripIndent()
    }

    def 'can consume recommended product dependencies project'() {
        setup:
        file("versions.props") << """
            org.slf4j:* = 1.7.24
        """.stripIndent()

        buildFile << """
            allprojects {
                version = '1.0.0'
            }
        """.stripIndent()

        addSubproject('api', """
            apply plugin: 'java'
            apply plugin: 'com.palantir.sls-recommended-dependencies'
            
            dependencies {
                compile 'org.slf4j:slf4j-api'
            }
            
            recommendedProductDependencies {
                productDependency {
                    productGroup = 'org'
                    productName = 'product'
                    minimumVersion = '1.1.0'
                    maximumVersion = '1.x.x'
                }
            }
        """.stripIndent())

        addSubproject('service', """
            apply plugin: 'java'
            apply plugin: 'com.palantir.sls-java-service-distribution'
            
            dependencies {
                // Gets picked up by the productDependenciesConfig which is runtimeClasspath
                compile project(':api')
            }
        """.stripIndent())

        expect:
        def wroteLocks = runTasks('--write-locks')
        // Maybe this is a bit too much but for a fixed version of sls-packaging, we expect this to not change
        wroteLocks.tasks(TaskOutcome.SUCCESS).collect { it.path } as Set == [
                ':service:createManifest',
                ':api:configureProductDependencies',
        ] as Set
        // Ensure that 'jar' was not run on the API project
        wroteLocks.task(':api:jar') == null

        runTasks('createManifest', 'verifyLocks')
    }
}
