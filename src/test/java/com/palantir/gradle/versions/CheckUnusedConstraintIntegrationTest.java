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
import com.palantir.gradle.testing.junit.AdditionallyRunWithGradle;
import com.palantir.gradle.testing.junit.DisabledConfigurationCache;
import com.palantir.gradle.testing.junit.GradlePluginTests;
import com.palantir.gradle.testing.project.RootProject;
import com.palantir.gradle.testing.project.SubProject;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@GradlePluginTests
@AdditionallyRunWithGradle("9.3.0")
class CheckUnusedConstraintIntegrationTest {

    @BeforeEach
    void setup(RootProject rootProject) {
        rootProject.buildGradle().plugins().add("java");
        rootProject.buildGradle().plugins().add("com.palantir.consistent-versions");

        rootProject.buildGradle().append("""
            repositories {
                mavenCentral()
                maven { url "%s/maven" }
            }

            // Get rid of deprecation warnings for Gradle 7+
            versionRecommendations {
                excludeConfigurations 'compile', 'runtime', 'testCompile', 'testRuntime'
            }
            """, rootProject.directory(".").path().toUri());

        rootProject.gradlePropertiesFile().setProperty("ignoreLockFile", "true");
    }

    private InvocationResult buildSucceed(GradleInvoker gradle) {
        InvocationResult result = gradle.withArgs("checkUnusedConstraints").buildsSuccessfully();
        assertThat(result).task(":checkUnusedConstraints").succeeded();
        return result;
    }

    private void buildAndFailWith(GradleInvoker gradle, String error) {
        InvocationResult result = gradle.withArgs("checkUnusedConstraints").buildsWithFailure();
        assertThat(result).output().contains(error);
    }

    private void buildWithFixWorks(GradleInvoker gradle, RootProject rootProject) {
        List<String> currentVersionsProps =
                rootProject.file("versions.props").text().lines().toList();
        // Check that running with --fix modifies the file
        gradle.withArgs("checkUnusedConstraints", "--fix").buildsSuccessfully();
        List<String> newVersionsProps =
                rootProject.file("versions.props").text().lines().toList();
        assertThat(newVersionsProps).isNotEqualTo(currentVersionsProps);

        // Check that the task now succeeds
        gradle.withArgs("checkUnusedConstraints").buildsSuccessfully();
    }

    @Test
    void checkVersionsProps_does_not_resolve_artifacts(GradleInvoker gradle, RootProject rootProject) {
        rootProject.buildGradle().append("""
            dependencies {
                implementation 'com.palantir.product:foo:1.0.0'
            }
            """);

        rootProject.file("versions.props").overwrite("");

        // We're not producing a jar for this dependency, so artifact resolution would fail
        rootProject
                .file("maven/com/palantir/product/foo/1.0.0/foo-1.0.0.pom")
                .overwrite(pomWithJarPackaging("com.palantir.product", "foo", "1.0.0"));

        buildSucceed(gradle);
    }

    @Test
    void task_should_run_as_part_of_check(GradleInvoker gradle) {
        InvocationResult result = gradle.withArgs("check", "-m").buildsSuccessfully();
        assertThat(result).output().contains(":checkUnusedConstraints");
    }

    @Test
    void version_props_conflict_should_succeed(GradleInvoker gradle, RootProject rootProject) {
        rootProject.file("versions.props").overwrite("""
            com.fasterxml.jackson.*:* = 2.9.3
            com.fasterxml.jackson.core:jackson-annotations = 2.9.5
            """);

        rootProject.buildGradle().append("""
            dependencies {
                implementation 'com.fasterxml.jackson.core:jackson-databind'
            }
            """);

        buildSucceed(gradle);
    }

    @Test
    @DisabledConfigurationCache("Test modifies versions.props via --fix flag which invalidates cache")
    void most_specific_matching_version_should_win(GradleInvoker gradle, RootProject rootProject) {
        rootProject.file("versions.props").overwrite("""
            org.slf4j:slf4j-api = 1.7.25
            org.slf4j:* = 1.7.20
            """);

        rootProject.buildGradle().append("""
            dependencies {
                implementation 'org.slf4j:slf4j-api'
            }
            """);

        buildAndFailWith(gradle, "There are unused pins in your versions.props: \n[org.slf4j:*]");
        buildWithFixWorks(gradle, rootProject);
        assertThat(rootProject.file("versions.props").text().trim()).isEqualTo("org.slf4j:slf4j-api = 1.7.25");
    }

    @Test
    @DisabledConfigurationCache("Test modifies versions.props via --fix flag which invalidates cache")
    void most_specific_glob_should_win(GradleInvoker gradle, RootProject rootProject) {
        rootProject.file("versions.props").overwrite("""
            org.slf4j:slf4j-* = 1.7.25
            org.slf4j:* = 1.7.20
            """);

        rootProject.buildGradle().append("""
            dependencies {
                implementation 'org.slf4j:slf4j-api'
                implementation 'org.slf4j:slf4j-jdk14'
            }
            """);

        buildAndFailWith(gradle, "There are unused pins in your versions.props: \n[org.slf4j:*]");
        buildWithFixWorks(gradle, rootProject);
        assertThat(rootProject.file("versions.props").text().trim()).isEqualTo("org.slf4j:slf4j-* = 1.7.25");
    }

    @Test
    @DisabledConfigurationCache("Test modifies versions.props via --fix flag which invalidates cache")
    void unused_version_should_fail(GradleInvoker gradle, RootProject rootProject) {
        rootProject.file("versions.props").overwrite("notused:atall = 42.42");

        buildAndFailWith(gradle, "There are unused pins in your versions.props");
        buildWithFixWorks(gradle, rootProject);
    }

    @Test
    void unused_check_should_use_exact_matching(GradleInvoker gradle, RootProject rootProject) {
        rootProject.file("versions.props").overwrite("""
            com.google.guava:guava-testlib = 23.0
            com.google.guava:guava = 22.0
            """);

        rootProject.buildGradle().append("""
            dependencies {
                implementation 'com.google.guava:guava'
                implementation 'com.google.guava:guava-testlib'
            }
            """);

        buildSucceed(gradle);
    }

    @Test
    void checkUnusedConstraints_works_in_multiproject_build_with_cross_project_deps(
            GradleInvoker gradle, RootProject rootProject, SubProject foo, SubProject bar) {
        rootProject.file("versions.props").overwrite("""
            com.google.guava:guava = 33.0.0-jre
            org.slf4j:slf4j-api = 2.0.9
            """);

        foo.buildGradle().plugins().add("java");
        foo.buildGradle().append("""
            repositories {
                mavenCentral()
            }
            dependencies {
                implementation 'com.google.guava:guava'
            }
            """);

        bar.buildGradle().plugins().add("java");
        bar.buildGradle().append("""
            repositories {
                mavenCentral()
            }
            dependencies {
                implementation 'org.slf4j:slf4j-api'
            }
            """);

        InvocationResult result =
                gradle.withArgs("checkUnusedConstraints", "--parallel").buildsSuccessfully();
        assertThat(result).task(":checkUnusedConstraints").succeeded();

        assertThat(result).output().doesNotContain("without an exclusive lock");
    }

    @Test
    void checkUnusedConstraints_with_platform_dependencies_on_root_project(
            GradleInvoker gradle, RootProject rootProject) {
        rootProject.file("versions.props").overwrite("""
            com.fasterxml.jackson.core:jackson-databind = 2.18.2
            """);

        // Add a custom resolvable configuration to the root project with a platform dependency
        rootProject.buildGradle().append("""
            configurations {
                rootConfiguration {
                    canBeConsumed = false
                    canBeResolved = true
                }
            }

            repositories {
                mavenCentral()
            }

            dependencies {
                rootConfiguration platform('com.fasterxml.jackson:jackson-bom:2.18.2')
                implementation 'com.fasterxml.jackson.core:jackson-databind'
            }
            """);

        InvocationResult result =
                gradle.withArgs("checkUnusedConstraints", "--parallel").buildsSuccessfully();

        assertThat(result).task(":checkUnusedConstraints").succeeded();
    }

    @Test
    void checkUnusedConstraints_does_not_depend_on_project_jar_tasks(
            GradleInvoker gradle, RootProject rootProject, SubProject lib) {
        rootProject.file("versions.props").overwrite("""
            com.google.guava:guava = 33.0.0-jre
            """);

        lib.buildGradle().plugins().add("java-library");
        lib.buildGradle().append("""
            repositories {
                mavenCentral()
            }
            dependencies {
                api 'com.google.guava:guava'
            }
            """);

        rootProject.buildGradle().plugins().add("java-library");
        rootProject.buildGradle().append("""
            dependencies {
                api project(':lib')
            }
            """);

        // Without the componentFilter fix, the artifact view includes project jars which
        // causes writeResolvedCoordinatesTask to unnecessarily depend on :lib:jar.
        // With the fix, project components are filtered out so :lib:jar is not triggered.
        InvocationResult result =
                gradle.withArgs("checkUnusedConstraints", "--parallel").buildsSuccessfully();
        assertThat(result)
                .output()
                .as("checkUnusedConstraints should not trigger :lib:jar")
                .doesNotContain(":lib:jar");
    }

    private static String pomWithJarPackaging(String group, String artifact, String version) {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
            <modelVersion>4.0.0</modelVersion>
            <groupId>%s</groupId>
            <artifactId>%s</artifactId>
            <packaging>jar</packaging>
            <version>%s</version>
            <description/>
            <dependencies/>
            </project>
            """.formatted(group, artifact, version);
    }
}
