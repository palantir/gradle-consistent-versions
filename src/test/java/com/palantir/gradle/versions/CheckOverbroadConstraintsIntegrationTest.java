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

package com.palantir.gradle.versions;

import static com.palantir.gradle.testing.assertion.GradlePluginTestAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import com.palantir.gradle.testing.execution.GradleInvoker;
import com.palantir.gradle.testing.execution.InvocationResult;
import com.palantir.gradle.testing.junit.DisabledConfigurationCache;
import com.palantir.gradle.testing.junit.GradlePluginTests;
import com.palantir.gradle.testing.project.RootProject;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@GradlePluginTests
@DisabledConfigurationCache
class CheckOverbroadConstraintsIntegrationTest {

    @BeforeEach
    void setup(RootProject project) {
        project.buildGradle().plugins().add("com.palantir.versions-lock").add("com.palantir.versions-props");

        project.file("versions.props").createEmpty();
        project.file("versions.lock").createEmpty();
    }

    private InvocationResult buildSucceed(GradleInvoker gradle) {
        InvocationResult result = gradle.withArgs("checkOverbroadConstraints").buildsSuccessfully();
        assertThat(result).task(":checkOverbroadConstraints").succeeded();
        return result;
    }

    private void buildAndFailWith(GradleInvoker gradle, String error) {
        InvocationResult result = gradle.withArgs("checkOverbroadConstraints").buildsWithFailure();
        assertThat(result).output().contains(error);
    }

    private void buildWithFixWorks(GradleInvoker gradle, RootProject project) {
        List<String> currentVersionsProps =
                List.of(project.file("versions.props").text().split("\n"));

        // Check that running with --fix modifies the file
        gradle.withArgs("checkOverbroadConstraints", "--fix").buildsSuccessfully();
        List<String> updatedVersionsProps =
                List.of(project.file("versions.props").text().split("\n"));
        assertThat(currentVersionsProps).isNotEqualTo(updatedVersionsProps);

        // Check that the task now succeeds
        gradle.withArgs("checkOverbroadConstraints").buildsSuccessfully();
    }

    @Test
    void task_should_run_as_part_of_check(GradleInvoker gradle) {
        InvocationResult result = gradle.withArgs("check", "-m").buildsSuccessfully();
        assertThat(result).output().contains(":checkOverbroadConstraints");
    }

    @Test
    void all_versions_are_pinned(GradleInvoker gradle, RootProject project) {
        project.file("versions.props").overwrite("""
            com.fasterxml.jackson.*:* = 2.9.3
            com.fasterxml.jackson.core:jackson-annotations = 2.9.5
            """);

        project.file("versions.lock").overwrite("""
            com.fasterxml.jackson.core:jackson-annotations:2.9.5 (2 constraints: abcdef0)
            com.fasterxml.jackson.core:jackson-core:2.9.3 (2 constraints: abcdef1)\
            """);

        buildSucceed(gradle);
    }

    @Test
    void not_all_versions_are_pinned_throws_error_and_fix_works_and_no_new_line_is_added(
            GradleInvoker gradle, RootProject project) {
        project.file("versions.props").overwrite("""
            com.fasterxml.jackson.*:* = 2.9.3
            """);

        project.file("versions.lock").overwrite("""
            com.fasterxml.jackson.core:jackson-annotations:2.9.5 (2 constraints: abcdef0)
            com.fasterxml.jackson.core:jackson-core:2.9.3 (2 constraints: abcdef1)\
            """);

        buildAndFailWith(gradle, """
            Over-broad version constraints found in versions.props.
            Over-broad constraints often arise due to wildcards in versions.props
            which apply to more dependencies than they should, this can lead to slow builds.
            The following additional pins are recommended:
            com.fasterxml.jackson.core:jackson-annotations = 2.9.5
            com.fasterxml.jackson.core:jackson-core = 2.9.3

            Run ./gradlew checkOverbroadConstraints --fix to add them.
            See https://github.com/palantir/gradle-consistent-versions?tab=readme-ov-file#gradlew-checkoverbroadconstraints for details\
            """);

        buildWithFixWorks(gradle, project);

        project.file("versions.props").assertThat().hasContent("""
            com.fasterxml.jackson.core:jackson-annotations = 2.9.5
            com.fasterxml.jackson.core:jackson-core = 2.9.3
            """);
    }

    @Test
    void fixes_are_inserted_in_the_correct_locations_new_lines_are_maintained(
            GradleInvoker gradle, RootProject project) {
        project.file("versions.props").overwrite("""

            com.random:* = 1.0.0
            com.fasterxml.jackson.*:* = 2.9.3

            # A random comment
            org.different:artifact = 2.0.0

            """);

        project.file("versions.lock").overwrite("""
            com.random:random:1.0.0 (2 constraints: abcdef0)
            org.different:artifact:2.0.0 (2 constraints: abcdef0)
            com.fasterxml.jackson.core:jackson-annotations:2.9.5 (2 constraints: abcdef0)
            com.fasterxml.jackson.core:jackson-core:2.9.3 (2 constraints: abcdef1)\
            """);

        buildWithFixWorks(gradle, project);

        project.file("versions.props").assertThat().hasContent("""

            com.random:* = 1.0.0
            com.fasterxml.jackson.core:jackson-annotations = 2.9.5
            com.fasterxml.jackson.core:jackson-core = 2.9.3

            # A random comment
            org.different:artifact = 2.0.0

            """);
    }
}
