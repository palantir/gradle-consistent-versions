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
import spock.lang.IgnoreIf

import static com.palantir.gradle.versions.GradleTestVersions.GRADLE_VERSIONS

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
                    mavenCentral()
                }
            }            
            plugins {
                id '${PLUGIN_NAME}'
                id 'com.palantir.sls-java-service-distribution' version '7.31.0' apply false
            }
            allprojects {
                repositories {
                    maven { url "file:///${mavenRepo.getAbsolutePath()}" }
                }
            }
        """.stripIndent(true)
    }

    @IgnoreIf(
            reason = """
                sls-packaging is creating a configuration as part of a task input, which is happening far too late. \
                Once gradle has done a resolution, it will not look at any new Configurations that have popped up \
                since then. See https://github.com/palantir/gradle-consistent-versions/pull/1443 for more details.""",
            value = { data.gradleVersionNumber.startsWith("9") })
    def '#gradleVersionNumber can consume recommended product dependencies project'() {
        setup:
        gradleVersion = gradleVersionNumber

        file("versions.props") << """
            org.slf4j:* = 1.7.24
        """.stripIndent(true)

        buildFile << """
            allprojects {
                version = '1.0.0'
            }
        """.stripIndent(true)

        addSubproject('api', """
            apply plugin: 'java'
            apply plugin: 'com.palantir.sls-recommended-dependencies'
            
            dependencies {
                implementation 'org.slf4j:slf4j-api'
            }
            
            recommendedProductDependencies {
                productDependency {
                    productGroup = 'org'
                    productName = 'product'
                    minimumVersion = '1.1.0'
                    maximumVersion = '1.x.x'
                }
            }
        """.stripIndent(true))

        addSubproject('service', """
            apply plugin: 'java'
            apply plugin: 'com.palantir.sls-java-service-distribution'
            
            dependencies {
                // Gets picked up by the productDependenciesConfig which is runtimeClasspath
                implementation project(':api')
            }
        """.stripIndent(true))

        expect:
        def wroteLocks = runTasks('--write-locks')
        // Maybe this is a bit too much but for a fixed version of sls-packaging, we expect this to not change
        wroteLocks.tasks(TaskOutcome.SUCCESS).collect { it.path } as Set == [
                ':api:compileRecommendedProductDependencies',
                ':api:processResources',
                ':service:mergeDiagnosticsJson',
                ':service:resolveProductDependencies',
                ':service:createManifest',
                ':api:classes',
                ':api:configureProductDependencies',
                ':api:jar',
                ':service:jar'
        ] as Set

        runTasks('createManifest', 'verifyLocks')

        where:
        gradleVersionNumber << GRADLE_VERSIONS

    }
}
