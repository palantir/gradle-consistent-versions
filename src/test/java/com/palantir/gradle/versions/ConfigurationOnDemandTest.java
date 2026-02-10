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

package com.palantir.gradle.versions;

import static com.palantir.gradle.testing.assertion.GradlePluginTestAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import com.palantir.gradle.testing.execution.GradleInvoker;
import com.palantir.gradle.testing.execution.InvocationResult;
import com.palantir.gradle.testing.junit.DisabledConfigurationCache;
import com.palantir.gradle.testing.junit.GradlePluginTests;
import com.palantir.gradle.testing.maven.MavenArtifact;
import com.palantir.gradle.testing.maven.MavenRepo;
import com.palantir.gradle.testing.project.RootProject;
import com.palantir.gradle.testing.project.SubProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * This tests the interaction of this plugin with Gradle's configuration-on-demand feature:
 * https://docs.gradle.org/current/userguide/multi_project_configuration_and_execution.html#sec:configuration_on_demand
 */
@GradlePluginTests
@DisabledConfigurationCache
class ConfigurationOnDemandTest {

    private static final String PLUGIN_NAME = "com.palantir.consistent-versions";

    /*
     * Project structure (arrows indicate dependencies):
     *
     *           upstream              unrelated
     *            ^    ^
     *            |    |
     *   downstream1  downstream2
     */
    @BeforeEach
    void setup(
            MavenRepo repo,
            RootProject rootProject,
            SubProject upstream,
            SubProject downstream1,
            SubProject downstream2,
            SubProject unrelated) {
        repo.publish(
                MavenArtifact.of("com.example:dependency-of-upstream:1.2.3"),
                MavenArtifact.of("com.example:dependency-of-upstream:100.1.1"),
                MavenArtifact.of("com.example:dependency-of-downstream1:1.2.3"),
                MavenArtifact.of("com.example:dependency-of-downstream2:1.2.3"),
                MavenArtifact.of("com.example:dependency-of-unrelated:1.2.3"),
                MavenArtifact.of("com.example:dep-with-version-bumped-by-unrelated:1.0.0"),
                MavenArtifact.of("com.example:dep-with-version-bumped-by-unrelated:1.1.0"),
                MavenArtifact.of("com.example:transitive-test-dep:1.0.0"),
                MavenArtifact.of("com.example:transitive-test-dep:1.1.0"),
                MavenArtifact.of("com.example:transitive-test-dep:1.2.0"));

        PomUtils.makePlatformPom(rootProject, repo, "org", "platform", "1.0");

        rootProject.buildGradle().plugins().add(PLUGIN_NAME);
        rootProject.buildGradle().append("""
            allprojects {
                repositories {
                    maven { url uri("%s") }
                }
            }
            subprojects {
                tasks.register('writeClasspath') {
                    doLast {
                        println(configurations.runtimeClasspath.getFiles())
                    }
                }
            }

            // Get rid of deprecation warnings for Gradle 7+
            versionRecommendations {
                excludeConfigurations 'compile', 'runtime', 'testCompile', 'testRuntime'
            }
            """, repo.path());

        rootProject.file("versions.props").overwrite("""
            com.example:dependency-of-upstream = 1.2.3
            com.example:dependency-of-downstream1 = 1.2.3
            com.example:dependency-of-downstream2 = 1.2.3
            com.example:dependency-of-unrelated = 1.2.3
            # 1.0.0 is a minimum, we expect this to be locked to 1.1.0
            com.example:dep-with-version-bumped-by-unrelated = 1.0.0
            """);

        upstream.buildGradle().plugins().add("java");
        upstream.buildGradle().append("""
            println 'configuring upstream'
            dependencies {
                implementation 'com.example:dependency-of-upstream'
            }
            """);

        downstream1.buildGradle().plugins().add("java");
        downstream1.buildGradle().append("""
            println 'configuring downstream1'
            dependencies {
                implementation project(':upstream')
                implementation 'com.example:dependency-of-downstream1'
            }
            """);

        downstream2.buildGradle().plugins().add("java");
        downstream2.buildGradle().append("""
            println 'configuring downstream2'
            dependencies {
                implementation project(':upstream')
                implementation 'com.example:dependency-of-downstream2'
                implementation 'com.example:dep-with-version-bumped-by-unrelated'
            }
            """);

        unrelated.buildGradle().plugins().add("java");
        unrelated.buildGradle().append("""
            println 'configuring unrelated'
            dependencies {
                implementation 'com.example:dependency-of-unrelated'
                implementation 'com.example:dep-with-version-bumped-by-unrelated:1.1.0'
            }
            """);

        rootProject.gradlePropertiesFile().appendProperty("org.gradle.configureondemand", "true");
    }

    @Test
    void can_write_locks(GradleInvoker gradle, RootProject rootProject) {
        InvocationResult result = gradle.withArgs("--write-locks").buildsSuccessfully();

        assertThat(result)
                .output()
                .contains("configuring upstream")
                .contains("configuring downstream1")
                .contains("configuring downstream2")
                .contains("configuring unrelated");

        rootProject.file("versions.lock").assertThat().exists();
        assertThat(rootProject.file("versions.lock").text())
                .contains("com.example:dependency-of-upstream:1.2.3")
                .contains("com.example:dep-with-version-bumped-by-unrelated:1.1.0");
    }

    @Test
    void can_write_locks_when_a_task_in_one_project_is_specified(GradleInvoker gradle, RootProject rootProject) {
        gradle.withArgs(":downstream1:build", "--write-locks").buildsSuccessfully();

        rootProject.file("versions.lock").assertThat().exists();
        assertThat(rootProject.file("versions.lock").text())
                .contains("com.example:dependency-of-unrelated:1.2.3")
                .contains("com.example:dep-with-version-bumped-by-unrelated:1.1.0");
    }

    @Test
    void applying_the_plugin_does_not_force_all_projects_to_be_configured(GradleInvoker gradle) {
        gradle.withArgs("--write-locks").buildsSuccessfully();
        // Both absolute and relative formats work, as long as Gradle is run from the root project directory
        InvocationResult result1 = gradle.withArgs(":downstream1:build").buildsSuccessfully();
        InvocationResult result2 = gradle.withArgs("downstream1:build").buildsSuccessfully();

        assertThat(result1)
                .output()
                .contains("configuring upstream")
                .contains("configuring downstream1")
                .doesNotContain("configuring downstream2")
                .doesNotContain("configuring unrelated");

        assertThat(result2)
                .output()
                .contains("configuring upstream")
                .contains("configuring downstream1")
                .doesNotContain("configuring downstream2")
                .doesNotContain("configuring unrelated");
    }

    @Test
    void resolving_a_classpath_does_not_force_all_projects_to_be_configured(GradleInvoker gradle) {
        gradle.withArgs("--write-locks").buildsSuccessfully();
        InvocationResult result = gradle.withArgs(":downstream1:writeClasspath").buildsSuccessfully();

        assertThat(result)
                .output()
                .contains("configuring upstream")
                .contains("configuring downstream1")
                .doesNotContain("configuring downstream2")
                .doesNotContain("configuring unrelated");
    }

    // after_lockfile_is_written_versions_constraints_due_to_non_configured_projects_are_still_respected
    @Test
    void after_lockfile_is_written_versions_constraints_due_to_non_configured_projects_are_still_respected(
            GradleInvoker gradle) {
        gradle.withArgs("--write-locks").buildsSuccessfully();
        InvocationResult result = gradle.withArgs(":downstream2:writeClasspath").buildsSuccessfully();

        // Version used is 1.1.0 due to the unrelated project
        assertThat(result)
                .output()
                .contains("dep-with-version-bumped-by-unrelated-1.1.0.jar")
                .doesNotContain("configured unrelated");
    }

    @Test
    void transitive_dependencies_cause_upstream_projects_to_be_configured_sufficiently_early(
            GradleInvoker gradle, SubProject projectA, SubProject projectB, SubProject projectC, SubProject projectU) {
        projectA.buildGradle().plugins().add("java");
        projectA.buildGradle().append("""
            dependencies {
                implementation 'com.example:transitive-test-dep:1.0.0'
            }
            """);

        projectB.buildGradle().plugins().add("java");
        projectB.buildGradle().append("""
            dependencies {
                implementation project(':projectA')
            }
            """);

        projectC.buildGradle().plugins().add("java");
        projectC.buildGradle().append("""
            dependencies {
                implementation project(':projectB')
            }
            tasks.register('writeClasspathOfA') {
                doLast {
                    println project(':projectA').configurations.runtimeClasspath.files
                }
            }
            """);

        projectU.buildGradle().plugins().add("java");
        projectU.buildGradle().append("""
            dependencies {
                implementation 'com.example:transitive-test-dep:1.1.0'
            }
            """);

        gradle.withArgs("--write-locks").buildsSuccessfully();
        InvocationResult result = gradle.withArgs(":projectC:writeClasspathOfA").buildsSuccessfully();

        // Version used should be 1.1.0, indicating that the version.lock constraint was applied
        assertThat(result).output().contains("transitive-test-dep-1.1.0.jar");
    }

    @Test
    void task_dependencies_cause_upstream_projects_to_be_configured_sufficiently_early(
            GradleInvoker gradle, SubProject projectA, SubProject projectB, SubProject projectC, SubProject projectU) {
        projectA.buildGradle().plugins().add("java");
        projectA.buildGradle().append("""
            dependencies {
                implementation 'com.example:transitive-test-dep:1.0.0'
            }
            """);

        projectB.buildGradle().append("""
            tasks.register('foo') {
                dependsOn ':projectA:writeClasspath'
            }
            """);

        projectC.buildGradle().append("""
            tasks.register('bar') {
                dependsOn ':projectB:foo'
            }
            """);

        projectU.buildGradle().plugins().add("java");
        projectU.buildGradle().append("""
            dependencies {
                implementation 'com.example:transitive-test-dep:1.1.0'
            }
            """);

        gradle.withArgs("--write-locks").buildsSuccessfully();
        InvocationResult result = gradle.withArgs(":projectC:bar").buildsSuccessfully();

        // Version used should be 1.1.0, indicating that the version.lock constraint was applied
        assertThat(result).output().contains("transitive-test-dep-1.1.0.jar");
    }

    @Test
    void verification_tasks_pass_when_all_projects_are_configured(GradleInvoker gradle) {
        gradle.withArgs("--write-locks").buildsSuccessfully();
        // Note: Not specifying the project causes all projects to be configured regardless of CoD
        InvocationResult result =
                gradle.withArgs("checkUnusedConstraints", "verifyLocks").buildsSuccessfully();

        assertThat(result).output().contains("configuring upstream");
        assertThat(result).task(":checkUnusedConstraints").succeeded();
        assertThat(result).task(":verifyLocks").succeeded();
    }

    @Test
    void checkUnusedConstraints_succeeds_when_not_all_projects_are_configured(GradleInvoker gradle) {
        gradle.withArgs("--write-locks").buildsSuccessfully();
        InvocationResult result = gradle.withArgs(":checkUnusedConstraints").buildsSuccessfully();

        assertThat(result).task(":checkUnusedConstraints").succeeded();
        assertThat(result).output().contains("configuring upstream");
    }

    @Test
    void verifyLocks_fails_and_warns_when_not_all_projects_are_configured(GradleInvoker gradle) {
        gradle.withArgs("--write-locks").buildsSuccessfully();
        InvocationResult result = gradle.withArgs(":verifyLocks").buildsWithFailure();

        assertThat(result)
                .output()
                .doesNotContain("configuring upstream")
                .contains("All projects must have been configured for this task to work correctly, but due to "
                        + "Gradle configuration-on-demand, not all projects were configured.");
        assertThat(result).task(":verifyLocks").failed();
    }

    // As failing tasks can't be considered UP-TO-DATE, we only need to check the case where the task passing
    // is followed by the task running with incomplete configuration.
    @Test
    void verification_tasks_are_not_up_to_date_when_the_set_of_configured_projects_differs(GradleInvoker gradle) {
        gradle.withArgs("--write-locks").buildsSuccessfully();
        gradle.withArgs("build").buildsSuccessfully();

        gradle.withArgs(":verifyLocks").buildsWithFailure();
    }

    @Test
    void the_why_task_works_when_all_projects_are_configured(GradleInvoker gradle) {
        gradle.withArgs("--write-locks").buildsSuccessfully();
        InvocationResult result = gradle.withArgs("why", "--hash=0805f935").buildsSuccessfully();

        assertThat(result).task(":why").succeeded();
    }

    @Test
    void the_why_task_somehow_forces_all_projects_to_be_configured(GradleInvoker gradle) {
        gradle.withArgs("--write-locks").buildsSuccessfully();
        InvocationResult result = gradle.withArgs(":why", "--hash=0805f935").buildsSuccessfully();

        assertThat(result)
                .output()
                .contains("configuring upstream")
                .contains("configuring downstream1")
                .contains("configuring downstream2")
                .contains("configuring unrelated")
                .contains("com.example:dependency-of-unrelated:1.2.3\n\tprojects -> 1.2.3");
        assertThat(result).task(":why").succeeded();
    }
}
