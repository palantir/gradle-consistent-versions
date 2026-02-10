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

import com.palantir.gradle.testing.execution.GradleInvoker;
import com.palantir.gradle.testing.execution.InvocationResult;
import com.palantir.gradle.testing.junit.DisabledConfigurationCache;
import com.palantir.gradle.testing.junit.GradlePluginTests;
import com.palantir.gradle.testing.project.RootProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link GetVersionPlugin}. Since GetVersionPlugin does not have its own plugin ID, we apply
 * {@code com.palantir.consistent-versions} which internally applies it.
 */
@GradlePluginTests
@DisabledConfigurationCache
class GetVersionPluginIntegrationTest {

    @BeforeEach
    void setup(RootProject project) {
        project.buildGradle().plugins().add("com.palantir.consistent-versions");
        project.file("versions.props").createEmpty();
        project.file("versions.lock").createEmpty();
    }

    @Test
    void apply_does_not_throw_exceptions(GradleInvoker gradle) {
        gradle.withArgs("help").buildsSuccessfully();
    }

    @Test
    void apply_is_idempotent(GradleInvoker gradle) {
        // Gradle plugin manager handles idempotent application, so applying
        // com.palantir.consistent-versions (which applies GetVersionPlugin) is sufficient
        gradle.withArgs("help").buildsSuccessfully();
    }

    @Test
    void get_version_is_callable_from_groovy_with_string_and_configuration_args(
            GradleInvoker gradle, RootProject project) {
        project.buildGradle().plugins().add("java");
        project.buildGradle().append("""
            tasks.register('callGetVersion') {
                doLast {
                    project.ext['getVersion']('com.google.guava:guava', configurations.runtimeClasspath)
                }
            }
            """);
        InvocationResult result = gradle.withArgs("callGetVersion").buildsWithFailure();
        assertThat(result)
                .output()
                .contains("Unable to find 'com.google.guava:guava' in configuration ':runtimeClasspath'");
    }

    @Test
    void get_version_is_callable_from_groovy_with_two_string_args_and_configuration_arg(
            GradleInvoker gradle, RootProject project) {
        project.buildGradle().plugins().add("java");
        project.buildGradle().append("""
            tasks.register('callGetVersion') {
                doLast {
                    project.ext['getVersion']('com.google.guava', 'guava', configurations.runtimeClasspath)
                }
            }
            """);
        InvocationResult result = gradle.withArgs("callGetVersion").buildsWithFailure();
        assertThat(result)
                .output()
                .contains("Unable to find 'com.google.guava:guava' in configuration ':runtimeClasspath'");
    }

    @Test
    void get_version_is_callable_from_groovy_with_two_string_args(GradleInvoker gradle, RootProject project) {
        project.buildGradle().plugins().add("java");
        project.buildGradle().append("""
            tasks.register('callGetVersion') {
                doLast {
                    project.ext['getVersion']('com.google.guava', 'guava')
                }
            }
            """);
        InvocationResult result = gradle.withArgs("callGetVersion").buildsWithFailure();
        assertThat(result)
                .output()
                .contains("Unable to find 'com.google.guava:guava' in configuration ':unifiedClasspath'");
    }
}
