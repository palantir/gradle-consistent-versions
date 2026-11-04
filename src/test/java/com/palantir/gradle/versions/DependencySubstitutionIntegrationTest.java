/*
 * (c) Copyright 2026 Palantir Technologies Inc. All rights reserved.
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
import com.palantir.gradle.testing.files.properties.PropertiesFile;
import com.palantir.gradle.testing.junit.DisabledConfigurationCache;
import com.palantir.gradle.testing.junit.GradlePluginTests;
import com.palantir.gradle.testing.maven.MavenArtifact;
import com.palantir.gradle.testing.maven.MavenRepo;
import com.palantir.gradle.testing.project.RootProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@GradlePluginTests
@DisabledConfigurationCache(
        "Writing versions.lock invalidates the configuration cache, and why is not compatible with it")
class DependencySubstitutionIntegrationTest {
    private PropertiesFile versionsProps;

    @BeforeEach
    void setup(RootProject rootProject, MavenRepo repo) {
        repo.publish(
                MavenArtifact.builder()
                        .coordinate("test:consumer:1.0")
                        .addDependency("old:library:1.0")
                        .build(),
                MavenArtifact.of("replacement:library:1.0"),
                MavenArtifact.of("replacement:library:1.5"),
                MavenArtifact.of("replacement:library:2.0"));

        rootProject.buildGradle().plugins().add("java").add("com.palantir.consistent-versions");
        rootProject.buildGradle().withMavenRepo(repo);
        rootProject.buildGradle().append("""
            dependencies {
                implementation 'test:consumer'
            }
            """);
        versionsProps = rootProject.propertiesFile("versions.props").setProperty("test:consumer", "1.0");
    }

    @Nested
    class FixedVersionSubstitution {
        @BeforeEach
        void setup(RootProject rootProject) {
            rootProject.buildGradle().append("""
                configurations.configureEach {
                    resolutionStrategy.dependencySubstitution {
                        it.substitute(it.module('old:library')).using(it.module('replacement:library:2.0'))
                    }
                }
                """);
        }

        @Test
        void exact_pin_on_substituted_module_is_kept_so_locks_stay_up_to_date(GradleInvoker gradle) {
            versionsProps.setProperty("old:library", "1.0");

            gradle.withArgs("checkUnusedConstraints", "--fix", "--write-locks").buildsSuccessfully();

            gradle.withArgs("verifyLocks").buildsSuccessfully();
        }

        @Test
        void glob_pin_on_substituted_module_is_unused(GradleInvoker gradle) {
            versionsProps.setProperty("old:*", "1.0");

            assertThat(gradle.withArgs("checkUnusedConstraints", "--write-locks")
                            .buildsWithFailure())
                    .output()
                    .contains("There are unused pins in your versions.props: \n[old:*]");
        }
    }

    @Nested
    class RenameKeepingRequestedVersion {
        @BeforeEach
        void setup(RootProject rootProject) {
            rootProject.buildGradle().append("""
                configurations.configureEach {
                    resolutionStrategy.eachDependency { details ->
                        if (details.requested.group == 'old') {
                            details.useTarget(group: 'replacement', name: 'library', version: details.requested.version)
                        }
                    }
                }
                """);
        }

        @Test
        void pin_on_renamed_module_is_used(GradleInvoker gradle, RootProject rootProject) {
            versionsProps.setProperty("old:library", "1.5");

            gradle.withArgs("checkUnusedConstraints", "--write-locks").buildsSuccessfully();

            rootProject.file("versions.lock").assertThat().content().contains("replacement:library:1.5");
        }

        @Test
        void renamed_request_is_a_constraint(GradleInvoker gradle, RootProject rootProject) {
            gradle.withArgs("writeVersionsLocks").buildsSuccessfully();

            rootProject
                    .file("versions.lock")
                    .assertThat()
                    .content()
                    .contains("replacement:library:1.0 (1 constraints:");
            assertThat(gradle.withArgs("why", "--dependency", "replacement:library")
                            .buildsSuccessfully())
                    .output()
                    .contains("test:consumer -> 1.0");
        }
    }
}
