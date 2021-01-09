/*
 * (c) Copyright 2021 Palantir Technologies Inc. All rights reserved.
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
import spock.lang.Unroll

import static com.palantir.gradle.versions.GradleTestVersions.GRADLE_VERSIONS
import static com.palantir.gradle.versions.PomUtils.makePlatformPom

/**
 * This tests the interaction of this plugin with Gradle's configuration-on-demand feature:
 * https://docs.gradle.org/current/userguide/multi_project_configuration_and_execution.html#sec:configuration_on_demand
 */
@Unroll
class ConfigurationOnDemandSpec extends IntegrationSpec {

    static def PLUGIN_NAME = "com.palantir.consistent-versions"
    private File mavenRepo

    /*
     * Project structure (arrows indicate dependencies):
     *
     *           upstream              unrelated
     *            ^    ^
     *            |    |
     *   downstream1  downstream2
     */
    void setup() {
        mavenRepo = generateMavenRepo(
                'com.example:dependency-of-upstream:1.2.3',
                'com.example:dependency-of-upstream:100.1.1',
                'com.example:dependency-of-downstream1:1.2.3',
                'com.example:dependency-of-downstream2:1.2.3',
                'com.example:dependency-of-unrelated:1.2.3',
                'com.example:dep-with-version-bumped-by-unrelated:1.0.0',
                'com.example:dep-with-version-bumped-by-unrelated:1.1.0',
        )

        makePlatformPom(mavenRepo, "org", "platform", "1.0")

        buildFile.text = """
            plugins {
                id '${PLUGIN_NAME}'
            }
            allprojects {
                repositories {
                    maven { url "file:///${mavenRepo.getAbsolutePath()}" }
                }
            }
            subprojects {
                tasks.register('writeClasspath') {
                    doLast {
                        println(configurations.runtimeClasspath.getFiles())
                    }
                }
            }
        """.stripIndent()

        file('versions.props').text = """
            com.example:dependency-of-upstream = 1.2.3
            com.example:dependency-of-downstream1 = 1.2.3
            com.example:dependency-of-downstream2 = 1.2.3
            com.example:dependency-of-unrelated = 1.2.3
            # 1.0.0 is a minimum, we expect this to be locked to 1.1.0
            com.example:dep-with-version-bumped-by-unrelated = 1.0.0
        """.stripIndent()

        addSubproject("upstream", """
            plugins {
                id 'java'
            }
            println 'configuring upstream'
            dependencies {
                implementation 'com.example:dependency-of-upstream'
            }
        """.stripIndent())

        addSubproject("downstream1", """
            plugins {
                id 'java'
            }
            println 'configuring downstream1'
            dependencies {
                implementation project(':upstream')
                implementation 'com.example:dependency-of-downstream1'
            }
        """.stripIndent())

        addSubproject("downstream2", """
            plugins {
                id 'java'
            }
            println 'configuring downstream2'
            dependencies {
                implementation project(':upstream')
                implementation 'com.example:dependency-of-downstream2'
                implementation 'com.example:dep-with-version-bumped-by-unrelated'
            }
        """.stripIndent())

        addSubproject("unrelated", """
            plugins {
                id 'java'
            }
            println 'configuring unrelated'
            dependencies {
                implementation 'com.example:dependency-of-unrelated'
                implementation 'com.example:dep-with-version-bumped-by-unrelated:1.1.0'
            }
        """.stripIndent())

        file("gradle.properties").text = """
            org.gradle.configureondemand=true
        """.stripIndent()
    }

    def '#gradleVersionNumber: can write locks'() {
        setup:
        gradleVersion = gradleVersionNumber

        when:
        runTasks('--write-locks')

        then:
        new File(projectDir, "versions.lock").exists()
        println("versions.lock contents:")
        println(new File(projectDir, "versions.lock").readLines().join("\n"))
        file("versions.lock").text.contains("com.example:dependency-of-upstream:1.2.3")
        file("versions.lock").text.contains("com.example:dep-with-version-bumped-by-unrelated:1.1.0")

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    def '#gradleVersionNumber: can write locks when a task in one project is specified'() {
        setup:
        gradleVersion = gradleVersionNumber

        when:
        runTasks(':downstream1:build', '--write-locks')

        then:
        new File(projectDir, "versions.lock").exists()
        file("versions.lock").text.contains("com.example:dependency-of-unrelated:1.2.3")
        file("versions.lock").text.contains("com.example:dep-with-version-bumped-by-unrelated:1.1.0")

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    def '#gradleVersionNumber: applying the plugin does not force all projects to be configured'() {
        setup:
        gradleVersion = gradleVersionNumber

        when:
        BuildResult result = runTasks(':downstream1:build')

        then:
        result.output.contains('configured upstream')
        result.output.contains('configured downstream1')
        !result.output.contains('configured downstream2')
        !result.output.contains('configured unrelated')

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    def '#gradleVersionNumber: after lockfile is written, versions constraints due to non-configured projects are still respected'() {
        setup:
        gradleVersion = gradleVersionNumber

        when:
        runTasks('--write-locks')
        BuildResult result = runTasks(':downstream2:writeClasspath')

        then:
        // Version used is 1.1.0 due to the unrelated project
        result.output.contains('dep-with-version-bumped-by-unrelated-1.1.0.jar')
        !result.output.contains('configured unrelated')

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    def '#gradleVersionNumber: verification tasks pass when all projects are configured'() {
        setup:
        gradleVersion = gradleVersionNumber

        when:
        runTasks('--write-locks')
        // Note: Not specifying the project causes all projects to be configured regardless of CoD
        BuildResult result = runTasks('checkUnusedConstraints', 'verifyLocks')

        then:
        result.output.contains('configuring upstream')
        result.task(':checkUnusedConstraints').outcome == TaskOutcome.SUCCESS
        result.task(':verifyLocks').outcome == TaskOutcome.SUCCESS

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    def '#gradleVersionNumber: verification tasks fail and warn when not all projects are configured'() {
        setup:
        gradleVersion = gradleVersionNumber

        when:
        runTasks('--write-locks')
        BuildResult result = runTasksAndFail(':checkUnusedConstraints', ':verifyLocks')

        then:
        !result.output.contains('configuring upstream')
        // TODO (#000): Check for the actual correct outputs
        result.task(':checkUnusedConstraints').outcome == TaskOutcome.FAILED
        result.task(':verifyLocks').outcome == TaskOutcome.FAILED

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }

    // TODO (#000): We should also test that these don't get incorrectly set as UP-TO-DATE across the
    // two situations.
    def '#gradleVersionNumber: verification tasks are not UP-TO-DATE when the set of configured projects differs'() {
        setup:
        gradleVersion = gradleVersionNumber

        when:
        runTasks('--write-locks')
        runTasks('build')
        BuildResult result = runTasksAndFail(':checkUnusedConstraints', ':verifyLocks')

        then:
        !result.output.contains('configuring upstream')
        // TODO (#000): Check for the actual correct outputs
        result.task(':checkUnusedConstraints').outcome == TaskOutcome.FAILED
        result.task(':verifyLocks').outcome == TaskOutcome.FAILED

        where:
        gradleVersionNumber << GRADLE_VERSIONS
    }
}
