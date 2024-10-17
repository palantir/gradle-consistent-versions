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

class CheckOverbroadConstraintsIntegrationSpec extends IntegrationSpec {

    def setup() {
        buildFile << """
            plugins {
                id 'com.palantir.versions-lock'
                id 'com.palantir.versions-props'
            }
        """.stripIndent(true)
        createFile('versions.props')
        createFile('versions.lock')
    }

    def buildSucceed() {
        BuildResult result = runTasks('checkOverbroadConstraints')
        result.task(':checkOverbroadConstraints').outcome == TaskOutcome.SUCCESS
        result
    }

    void buildAndFailWith(String error) {
        BuildResult result = runTasksAndFail('checkOverbroadConstraints')
        assert result.output.contains(error)
    }

    def buildWithFixWorks() {
        def currentVersionsProps = file('versions.props').readLines()
        // Check that running with --fix modifies the file
        runTasks('checkOverbroadConstraints', '--fix')
        assert file('versions.props').readLines() != currentVersionsProps

        // Check that the task now succeeds
        runTasks('checkOverbroadConstraints')
    }

    def 'Task should run as part of :check'() {
        expect:
        def result = runTasks('check', '-m')
        result.output.contains(':checkOverbroadConstraints')
    }

    def 'All versions are pinned'() {
        when:
        file('versions.props').text = """
            com.fasterxml.jackson.*:* = 2.9.3
            com.fasterxml.jackson.core:jackson-annotations = 2.9.5
        """.stripIndent(true)
        file('versions.lock').text = """
            com.fasterxml.jackson.core:jackson-annotations:2.9.5 (2 constraints: abcdef0)
            com.fasterxml.jackson.core:jackson-core:2.9.3 (2 constraints: abcdef1)
        """.stripIndent(true).trim()

        then:
        buildSucceed()
    }

    def 'Not all versions are pinned throws error and fix works'() {
        when:
        file('versions.props').text = """
            com.fasterxml.jackson.*:* = 2.9.3
        """.stripIndent(true)
        file('versions.lock').text = """
            com.fasterxml.jackson.core:jackson-annotations:2.9.5 (2 constraints: abcdef0)
            com.fasterxml.jackson.core:jackson-core:2.9.3 (2 constraints: abcdef1)
        """.stripIndent(true).trim()

        then:
        buildAndFailWith('Over-broad version constraints found in versions.props.\n'
                       + 'Over-broad constrains often arise due to wildcards in versions.props\n'
                       + 'which apply to more dependencies than they should, this can lead to slow builds.\n'
                       + 'The following additional pins are recommended:\n'
                       + '[com.fasterxml.jackson.core:jackson-annotations = 2.9.5]\n\n'
                       + 'Run ./gradlew checkOverbroadConstraints --fix to add them.See https://pl.ntr/2oX for details')
        buildWithFixWorks()
        file('versions.props').text.trim() == """
            com.fasterxml.jackson.*:* = 2.9.3
            com.fasterxml.jackson.core:jackson-annotations = 2.9.5
        """.stripIndent(true).trim()
    }
}
