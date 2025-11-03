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

import static org.assertj.core.api.Assertions.assertThat;

import com.palantir.gradle.testing.execution.GradleInvoker;
import com.palantir.gradle.testing.execution.InvocationResult;
import com.palantir.gradle.testing.execution.TaskOutcome;
import com.palantir.gradle.testing.junit.GradlePluginTests;
import com.palantir.gradle.testing.project.RootProject;
import com.palantir.gradle.testing.project.SubProject;
import java.io.File;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * This tests the interaction of this plugin with Gradle's configuration-on-demand feature:
 * https://docs.gradle.org/current/userguide/multi_project_configuration_and_execution.html#sec:configuration_on_demand
 */
@GradlePluginTests
class ConfigurationOnDemandSpec {

    private static final String PLUGIN_NAME = "com.palantir.consistent-versions";
    private File mavenRepo;

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
            RootProject rootProject,
            SubProject upstreamProject,
            SubProject downstream1Project,
            SubProject downstream2Project,
            SubProject unrelatedProject) {
        mavenRepo = MavenRepoUtils.generateMavenRepo(
                rootProject.path().resolve("build"),
                "com.example:dependency-of-upstream:1.2.3",
                "com.example:dependency-of-upstream:100.1.1",
                "com.example:dependency-of-downstream1:1.2.3",
                "com.example:dependency-of-downstream2:1.2.3",
                "com.example:dependency-of-unrelated:1.2.3",
                "com.example:dep-with-version-bumped-by-unrelated:1.0.0",
                "com.example:dep-with-version-bumped-by-unrelated:1.1.0",
                "com.example:transitive-test-dep:1.0.0",
                "com.example:transitive-test-dep:1.1.0",
                "com.example:transitive-test-dep:1.2.0");

        PomUtils.makePlatformPom(mavenRepo, "org", "platform", "1.0");

        rootProject.buildGradle().overwrite("""
            plugins {
                id '%s'
            }
            allprojects {
                repositories {
                    maven { url "file:///%s" }
                }
            }
            subprojects {
                tasks.register('writeClasspath') {
                    doLast {
                        println(configurations.runtimeClasspath.getFiles())
                    }
                }
            }
            \s\s\s\s
            // Get rid of deprecation warnings for Gradle 7+
            versionRecommendations {
                excludeConfigurations 'compile', 'runtime', 'testCompile', 'testRuntime'
            }
            """, PLUGIN_NAME, mavenRepo.getAbsolutePath());

        rootProject.file("versions.props").overwrite("""
            com.example:dependency-of-upstream = 1.2.3
            com.example:dependency-of-downstream1 = 1.2.3
            com.example:dependency-of-downstream2 = 1.2.3
            com.example:dependency-of-unrelated = 1.2.3
            # 1.0.0 is a minimum, we expect this to be locked to 1.1.0
            com.example:dep-with-version-bumped-by-unrelated = 1.0.0
            """);

        rootProject.settingsGradle().rootProjectName("test");
        rootProject.settingsGradle().include("upstream");
        rootProject.settingsGradle().include("downstream1");
        rootProject.settingsGradle().include("downstream2");
        rootProject.settingsGradle().include("unrelated");

        upstreamProject.buildGradle().append("""
            plugins {
                id 'java'
            }
            println 'configuring upstream'
            dependencies {
                implementation 'com.example:dependency-of-upstream'
            }
            """);

        downstream1Project.buildGradle().append("""
            plugins {
                id 'java'
            }
            println 'configuring downstream1'
            dependencies {
                implementation project(':upstream')
                implementation 'com.example:dependency-of-downstream1'
            }
            """);

        downstream2Project.buildGradle().append("""
            plugins {
                id 'java'
            }
            println 'configuring downstream2'
            dependencies {
                implementation project(':upstream')
                implementation 'com.example:dependency-of-downstream2'
                implementation 'com.example:dep-with-version-bumped-by-unrelated'
            }
            """);

        unrelatedProject.buildGradle().append("""
            plugins {
                id 'java'
            }
            println 'configuring unrelated'
            dependencies {
                implementation 'com.example:dependency-of-unrelated'
                implementation 'com.example:dep-with-version-bumped-by-unrelated:1.1.0'
            }
            """);

        rootProject.gradlePropertiesFile().append("""
            org.gradle.configureondemand=true
            """);
    }

    @Test
    void can_write_locks(GradleInvoker gradle, RootProject rootProject) {
        InvocationResult result = gradle.withArgs("--write-locks").buildsSuccessfully();

        assertThat(result.output()).contains("configuring upstream");
        assertThat(result.output()).contains("configuring downstream1");
        assertThat(result.output()).contains("configuring downstream2");
        assertThat(result.output()).contains("configuring unrelated");

        assertThat(rootProject.path().resolve("versions.lock")).exists();
        assertThat(rootProject.file("versions.lock").text()).contains("com.example:dependency-of-upstream:1.2.3");
        assertThat(rootProject.file("versions.lock").text())
                .contains("com.example:dep-with-version-bumped-by-unrelated:1.1.0");
    }

    @Test
    void can_write_locks_when_a_task_in_one_project_is_specified(GradleInvoker gradle, RootProject rootProject) {
        gradle.withArgs(":downstream1:build", "--write-locks").buildsSuccessfully();

        assertThat(rootProject.path().resolve("versions.lock")).exists();
        assertThat(rootProject.file("versions.lock").text()).contains("com.example:dependency-of-unrelated:1.2.3");
        assertThat(rootProject.file("versions.lock").text())
                .contains("com.example:dep-with-version-bumped-by-unrelated:1.1.0");
    }

    @Test
    void applying_the_plugin_does_not_force_all_projects_to_be_configured(GradleInvoker gradle) {
        gradle.withArgs("--write-locks").buildsSuccessfully();

        // Both absolute and relative formats work, as long as Gradle is run from the root project directory
        InvocationResult result1 = gradle.withArgs(":downstream1:build").buildsSuccessfully();
        InvocationResult result2 = gradle.withArgs("downstream1:build").buildsSuccessfully();

        assertThat(result1.output()).contains("configuring upstream");
        assertThat(result1.output()).contains("configuring downstream1");
        assertThat(result1.output()).doesNotContain("configuring downstream2");
        assertThat(result1.output()).doesNotContain("configuring unrelated");

        assertThat(result2.output()).contains("configuring upstream");
        assertThat(result2.output()).contains("configuring downstream1");
        assertThat(result2.output()).doesNotContain("configuring downstream2");
        assertThat(result2.output()).doesNotContain("configuring unrelated");
    }

    @Test
    void resolving_a_classpath_does_not_force_all_projects_to_be_configured(GradleInvoker gradle) {
        gradle.withArgs("--write-locks").buildsSuccessfully();

        InvocationResult result = gradle.withArgs(":downstream1:writeClasspath").buildsSuccessfully();

        assertThat(result.output()).contains("configuring upstream");
        assertThat(result.output()).contains("configuring downstream1");
        assertThat(result.output()).doesNotContain("configuring downstream2");
        assertThat(result.output()).doesNotContain("configuring unrelated");
    }

    @Test
    void after_lockfile_is_written_versions_constraints_due_to_non_configured_projects_are_still_respected(
            GradleInvoker gradle) {
        gradle.withArgs("--write-locks").buildsSuccessfully();

        InvocationResult result = gradle.withArgs(":downstream2:writeClasspath").buildsSuccessfully();

        // Version used is 1.1.0 due to the unrelated project
        assertThat(result.output()).contains("dep-with-version-bumped-by-unrelated-1.1.0.jar");
        assertThat(result.output()).doesNotContain("configured unrelated");
    }

    @Test
    void transitive_dependencies_cause_upstream_projects_to_be_configured_sufficiently_early(
            GradleInvoker gradle,
            RootProject rootProject,
            SubProject aProject,
            SubProject bProject,
            SubProject cProject,
            SubProject uProject) {
        rootProject.settingsGradle().include("a");
        rootProject.settingsGradle().include("b");
        rootProject.settingsGradle().include("c");
        rootProject.settingsGradle().include("u");

        aProject.buildGradle().append("""
            plugins { id 'java' }
            dependencies {
                implementation 'com.example:transitive-test-dep:1.0.0'
            }
            """);

        bProject.buildGradle().append("""
            plugins { id 'java' }
            dependencies {
                implementation project(':a')
            }
            """);

        cProject.buildGradle().append("""
            plugins { id 'java' }
            dependencies {
                implementation project(':b')
            }
            tasks.register('writeClasspathOfA') {
                doLast {
                    println project(':a').configurations.runtimeClasspath.files
                }
            }
            """);

        uProject.buildGradle().append("""
            plugins { id 'java' }
            dependencies {
                implementation 'com.example:transitive-test-dep:1.1.0'
            }
            """);

        gradle.withArgs("--write-locks").buildsSuccessfully();
        InvocationResult result = gradle.withArgs(":c:writeClasspathOfA").buildsSuccessfully();

        // Version used should be 1.1.0, indicating that the version.lock constraint was applied
        assertThat(result.output()).contains("transitive-test-dep-1.1.0.jar");
    }

    @Test
    void task_dependencies_cause_upstream_projects_to_be_configured_sufficiently_early(
            GradleInvoker gradle,
            RootProject rootProject,
            SubProject aProject,
            SubProject bProject,
            SubProject cProject,
            SubProject uProject) {
        rootProject.settingsGradle().include("a");
        rootProject.settingsGradle().include("b");
        rootProject.settingsGradle().include("c");
        rootProject.settingsGradle().include("u");

        aProject.buildGradle().append("""
            plugins { id 'java' }
            dependencies {
                implementation 'com.example:transitive-test-dep:1.0.0'
            }
            """);

        bProject.buildGradle().append("""
            tasks.register('foo') {
                dependsOn ':a:writeClasspath'
            }
            """);

        cProject.buildGradle().append("""
            tasks.register('bar') {
                dependsOn ':b:foo'
            }
            """);

        uProject.buildGradle().append("""
            plugins { id 'java' }
            dependencies {
                implementation 'com.example:transitive-test-dep:1.1.0'
            }
            """);

        gradle.withArgs("--write-locks").buildsSuccessfully();
        InvocationResult result = gradle.withArgs(":c:bar").buildsSuccessfully();

        // Version used should be 1.1.0, indicating that the version.lock constraint was applied
        assertThat(result.output()).contains("transitive-test-dep-1.1.0.jar");
    }

    @Test
    void verification_tasks_pass_when_all_projects_are_configured(GradleInvoker gradle) {
        gradle.withArgs("--write-locks").buildsSuccessfully();

        // Note: Not specifying the project causes all projects to be configured regardless of CoD
        InvocationResult result =
                gradle.withArgs("checkUnusedConstraints", "verifyLocks").buildsSuccessfully();

        assertThat(result.output()).contains("configuring upstream");
        assertThat(result.task(":checkUnusedConstraints"))
                .hasValueSatisfying(task -> assertThat(task.outcome()).isEqualTo(TaskOutcome.SUCCESS));
        assertThat(result.task(":verifyLocks"))
                .hasValueSatisfying(task -> assertThat(task.outcome()).isEqualTo(TaskOutcome.SUCCESS));
    }

    @Test
    void check_unused_constraints_fails_and_warns_when_not_all_projects_are_configured(GradleInvoker gradle) {
        gradle.withArgs("--write-locks").buildsSuccessfully();

        InvocationResult result = gradle.withArgs(":checkUnusedConstraints").buildsWithFailure();

        assertThat(result.output()).doesNotContain("configuring upstream");
        assertThat(result.task(":checkUnusedConstraints"))
                .hasValueSatisfying(task -> assertThat(task.outcome()).isEqualTo(TaskOutcome.FAILED));
        assertThat(result.output())
                .contains("The gradle-consistent-versions checkUnusedConstraints task "
                        + "must have all projects configured to work accurately, but due to Gradle "
                        + "configuration-on-demand, not all projects were configured.");
    }

    @Test
    void verify_locks_fails_and_warns_when_not_all_projects_are_configured(GradleInvoker gradle) {
        gradle.withArgs("--write-locks").buildsSuccessfully();

        InvocationResult result = gradle.withArgs(":verifyLocks").buildsWithFailure();

        assertThat(result.output()).doesNotContain("configuring upstream");
        assertThat(result.task(":verifyLocks"))
                .hasValueSatisfying(task -> assertThat(task.outcome()).isEqualTo(TaskOutcome.FAILED));
        assertThat(result.output())
                .contains("All projects must have been configured for this task to work correctly, but due to "
                        + "Gradle configuration-on-demand, not all projects were configured.");
    }

    @Test
    void verification_tasks_are_not_up_to_date_when_the_set_of_configured_projects_differs(GradleInvoker gradle) {
        gradle.withArgs("--write-locks").buildsSuccessfully();
        gradle.withArgs("build").buildsSuccessfully();

        gradle.withArgs(":checkUnusedConstraints").buildsWithFailure();
        gradle.withArgs(":verifyLocks").buildsWithFailure();
    }

    @Test
    void the_why_task_works_when_all_projects_are_configured(GradleInvoker gradle) {
        gradle.withArgs("--write-locks").buildsSuccessfully();

        InvocationResult result = gradle.withArgs("why", "--hash=0805f935").buildsSuccessfully();
        assertThat(result.task(":why"))
                .hasValueSatisfying(task -> assertThat(task.outcome()).isEqualTo(TaskOutcome.SUCCESS));
    }

    @Test
    void the_why_task_somehow_forces_all_projects_to_be_configured(GradleInvoker gradle) {
        gradle.withArgs("--write-locks").buildsSuccessfully();

        InvocationResult result = gradle.withArgs(":why", "--hash=0805f935").buildsSuccessfully();

        assertThat(result.output()).contains("configuring upstream");
        assertThat(result.output()).contains("configuring downstream1");
        assertThat(result.output()).contains("configuring downstream2");
        assertThat(result.output()).contains("configuring unrelated");
        assertThat(result.task(":why"))
                .hasValueSatisfying(task -> assertThat(task.outcome()).isEqualTo(TaskOutcome.SUCCESS));
        assertThat(result.output()).contains("com.example:dependency-of-unrelated:1.2.3\n\tprojects -> 1.2.3");
    }
}
