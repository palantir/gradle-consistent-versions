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

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.TaskOutcome

class CheckUnusedConstraintIntegrationSpec extends IntegrationSpec {

    def setup() {
        buildFile << """
            plugins {
                id 'java'
                id 'com.palantir.versions-lock'
                id 'com.palantir.versions-props'
            }

            repositories {
                mavenCentral()
                maven { url "${projectDir.toURI()}/maven" }
            }
        """.stripIndent()
        file('gradle.properties').text = """
        ignoreLockFile=true
        """.stripIndent()
        createFile('versions.props')
    }

    def buildSucceed() {
        BuildResult result = runTasks( 'checkUnusedConstraints')
        result.task(':checkUnusedConstraints').outcome == TaskOutcome.SUCCESS
        result
    }

    void buildAndFailWith(String error) {
        BuildResult result = runTasksAndFail('checkUnusedConstraints')
        assert result.output.contains(error)
    }

    def buildWithFixWorks() {
        def currentVersionsProps = file('versions.props').readLines()
        // Check that running with --fix modifies the file
        runTasks('checkUnusedConstraints', '--fix')
        assert file('versions.props').readLines() != currentVersionsProps

        // Check that the task now succeeds
        runTasks('checkUnusedConstraints')
    }

    def 'checkVersionsProps does not resolve artifacts'() {
        buildFile << """
            dependencies {
                implementation 'com.palantir.product:foo:1.0.0'
            }
        """.stripIndent()
        file("versions.props").text = ""

        // We're not producing a jar for this dependency, so artifact resolution would fail
        file('maven/com/palantir/product/foo/1.0.0/foo-1.0.0.pom') <<
                pomWithJarPackaging("com.palantir.product", "foo", "1.0.0")

        expect:
        buildSucceed()
    }

    def 'Task should run as part of :check'() {
        expect:
        def result = runTasks('check', '-m')
        result.output.contains(':checkUnusedConstraints')
    }

    def 'Version props conflict should succeed'() {
        when:
        file('versions.props').text = """
            com.fasterxml.jackson.*:* = 2.9.3
            com.fasterxml.jackson.core:jackson-annotations = 2.9.5
        """.stripIndent()
        buildFile << """
        dependencies {
            implementation 'com.fasterxml.jackson.core:jackson-databind'
        }""".stripIndent()

        then:
        buildSucceed()
    }

    def 'Most specific matching version should win'() {
        when:
        file('versions.props').text = """
            org.slf4j:slf4j-api = 1.7.25
            org.slf4j:* = 1.7.20
        """.stripIndent()

        buildFile << """
        dependencies {
            implementation 'org.slf4j:slf4j-api'
        }""".stripIndent()

        then:
        buildAndFailWith('There are unused pins in your versions.props: \n[org.slf4j:*]')
        buildWithFixWorks()
        file('versions.props').text.trim() == "org.slf4j:slf4j-api = 1.7.25"
    }

    def 'Most specific glob should win'() {
        when:
        file('versions.props').text = """
            org.slf4j:slf4j-* = 1.7.25
            org.slf4j:* = 1.7.20
        """.stripIndent()

        buildFile << """
        dependencies {
            implementation 'org.slf4j:slf4j-api'
            implementation 'org.slf4j:slf4j-jdk14'
        }""".stripIndent()

        then:
        buildAndFailWith('There are unused pins in your versions.props: \n[org.slf4j:*]')
        buildWithFixWorks()
        file('versions.props').text.trim() == "org.slf4j:slf4j-* = 1.7.25"
    }

    def 'Unused version should fail'() {
        when:
        file('versions.props').text = "notused:atall = 42.42"

        then:
        buildAndFailWith("There are unused pins in your versions.props")
        buildWithFixWorks()
    }

    def 'Unused check should use exact matching'() {
        when:
        file('versions.props').text = """
            com.google.guava:guava-testlib = 23.0
            com.google.guava:guava = 22.0
        """.stripIndent()
        buildFile << """
        dependencies {
            implementation 'com.google.guava:guava'
            implementation 'com.google.guava:guava-testlib'
        }""".stripIndent()

        then:
        buildSucceed()
    }

    static String pomWithJarPackaging(String group, String artifact, String version) {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
            <modelVersion>4.0.0</modelVersion>
            <groupId>$group</groupId>
            <artifactId>$artifact</artifactId>
            <packaging>jar</packaging>
            <version>$version</version>
            <description/>
            <dependencies/>
            </project>
        """.stripIndent()
    }
}
