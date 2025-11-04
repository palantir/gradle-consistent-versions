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
import com.palantir.gradle.testing.junit.GradlePluginTests;
import com.palantir.gradle.testing.project.RootProject;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@GradlePluginTests
final class CheckUnusedConstraintIntegrationSpec {

    @BeforeEach
    void setup(RootProject rootProject) throws IOException {
        rootProject.buildGradle().append("""
            plugins {
                id 'java'
                id 'com.palantir.versions-lock'
                id 'com.palantir.versions-props'
            }

            repositories {
                mavenCentral()
                maven { url "%s/maven" }
            }

            // Get rid of deprecation warnings for Gradle 7+
            versionRecommendations {
                excludeConfigurations 'compile', 'runtime', 'testCompile', 'testRuntime'
            }
            """, rootProject.path());

        rootProject.gradlePropertiesFile().overwrite("""
            ignoreLockFile=true
            """);

        rootProject.file("versions.props").createEmpty();
    }

    @Test
    void check_versions_props_does_not_resolve_artifacts(GradleInvoker gradle, RootProject project) throws IOException {
        project.buildGradle().append("""
            dependencies {
                implementation 'com.palantir.product:foo:1.0.0'
            }
            """);

        project.file("versions.props").overwrite("");

        // We're not producing a jar for this dependency, so artifact resolution would fail
        Files.createDirectories(project.path().resolve("maven/com/palantir/product/foo/1.0.0"));
        Files.writeString(
                project.path().resolve("maven/com/palantir/product/foo/1.0.0/foo-1.0.0.pom"),
                TestPomUtils.pomWithJarPackaging("com.palantir.product", "foo", "1.0.0"));

        InvocationResult result = gradle.withArgs("checkUnusedConstraints").buildsSuccessfully();

        assertThat(result).task(":checkUnusedConstraints").succeeded();
    }

    @Test
    void task_should_run_as_part_of_check(GradleInvoker gradle, RootProject _project) {
        InvocationResult result = gradle.withArgs("check", "-m").buildsSuccessfully();

        assertThat(result).output().contains(":checkUnusedConstraints");
    }

    @Test
    void version_props_conflict_should_succeed(GradleInvoker gradle, RootProject project) throws IOException {
        project.file("versions.props").overwrite("""
            com.fasterxml.jackson.*:* = 2.9.3
            com.fasterxml.jackson.core:jackson-annotations = 2.9.5
            """);

        project.buildGradle().append("""
            dependencies {
                implementation 'com.fasterxml.jackson.core:jackson-databind'
            }
            """);

        InvocationResult result = gradle.withArgs("checkUnusedConstraints").buildsSuccessfully();

        assertThat(result).task(":checkUnusedConstraints").succeeded();
    }

    @Test
    void most_specific_matching_version_should_win(GradleInvoker gradle, RootProject project) throws IOException {
        project.file("versions.props").overwrite("""
            org.slf4j:slf4j-api = 1.7.25
            org.slf4j:* = 1.7.20
            """);

        project.buildGradle().append("""
            dependencies {
                implementation 'org.slf4j:slf4j-api'
            }
            """);

        InvocationResult failure = gradle.withArgs("checkUnusedConstraints").buildsWithFailure();

        assertThat(failure).output().contains("There are unused pins in your versions.props: \n[org.slf4j:*]");

        // Check that running with --fix modifies the file
        String currentVersionsProps = project.file("versions.props").text();
        gradle.withArgs("checkUnusedConstraints", "--fix").buildsSuccessfully();
        assertThat(project.file("versions.props").text()).isNotEqualTo(currentVersionsProps);

        // Check that the task now succeeds
        gradle.withArgs("checkUnusedConstraints").buildsSuccessfully();

        assertThat(project.file("versions.props").text().trim()).isEqualTo("org.slf4j:slf4j-api = 1.7.25");
    }

    @Test
    void most_specific_glob_should_win(GradleInvoker gradle, RootProject project) throws IOException {
        project.file("versions.props").overwrite("""
            org.slf4j:slf4j-* = 1.7.25
            org.slf4j:* = 1.7.20
            """);

        project.buildGradle().append("""
            dependencies {
                implementation 'org.slf4j:slf4j-api'
                implementation 'org.slf4j:slf4j-jdk14'
            }
            """);

        InvocationResult failure = gradle.withArgs("checkUnusedConstraints").buildsWithFailure();

        assertThat(failure).output().contains("There are unused pins in your versions.props: \n[org.slf4j:*]");

        // Check that running with --fix modifies the file
        String currentVersionsProps = project.file("versions.props").text();
        gradle.withArgs("checkUnusedConstraints", "--fix").buildsSuccessfully();
        assertThat(project.file("versions.props").text()).isNotEqualTo(currentVersionsProps);

        // Check that the task now succeeds
        gradle.withArgs("checkUnusedConstraints").buildsSuccessfully();

        assertThat(project.file("versions.props").text().trim()).isEqualTo("org.slf4j:slf4j-* = 1.7.25");
    }

    @Test
    void unused_version_should_fail(GradleInvoker gradle, RootProject project) throws IOException {
        project.file("versions.props").overwrite("notused:atall = 42.42");

        InvocationResult failure = gradle.withArgs("checkUnusedConstraints").buildsWithFailure();

        assertThat(failure).output().contains("There are unused pins in your versions.props");

        // Check that running with --fix modifies the file
        String currentVersionsProps = project.file("versions.props").text();
        gradle.withArgs("checkUnusedConstraints", "--fix").buildsSuccessfully();
        assertThat(project.file("versions.props").text()).isNotEqualTo(currentVersionsProps);

        // Check that the task now succeeds
        gradle.withArgs("checkUnusedConstraints").buildsSuccessfully();
    }

    @Test
    void unused_check_should_use_exact_matching(GradleInvoker gradle, RootProject project) throws IOException {
        project.file("versions.props").overwrite("""
            com.google.guava:guava-testlib = 23.0
            com.google.guava:guava = 22.0
            """);

        project.buildGradle().append("""
            dependencies {
                implementation 'com.google.guava:guava'
                implementation 'com.google.guava:guava-testlib'
            }
            """);

        InvocationResult result = gradle.withArgs("checkUnusedConstraints").buildsSuccessfully();

        assertThat(result).task(":checkUnusedConstraints").succeeded();
    }
}
