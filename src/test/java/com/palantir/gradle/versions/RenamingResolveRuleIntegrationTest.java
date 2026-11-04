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
import com.palantir.gradle.testing.junit.AdditionallyRunWithGradle;
import com.palantir.gradle.testing.junit.DisabledConfigurationCache;
import com.palantir.gradle.testing.junit.GradlePluginTests;
import com.palantir.gradle.testing.maven.MavenArtifact;
import com.palantir.gradle.testing.maven.MavenRepo;
import com.palantir.gradle.testing.project.RootProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@GradlePluginTests
@AdditionallyRunWithGradle("7.6.4")
@DisabledConfigurationCache("why is not compatible with the configuration cache")
class RenamingResolveRuleIntegrationTest {
    @BeforeEach
    void setup(RootProject rootProject, MavenRepo repo) {
        repo.publish(
                MavenArtifact.builder()
                        .coordinate("test:consumer:1.0")
                        .addDependency("old:library:1.0")
                        .build(),
                MavenArtifact.of("replacement:library:1.0"),
                MavenArtifact.of("replacement:library:2.0"));
        rootProject.buildGradle().plugins().add("java").add("com.palantir.consistent-versions");
        rootProject.buildGradle().withMavenRepo(repo);
        rootProject.buildGradle().append("""
            dependencies {
                implementation 'test:consumer'
            }
            configurations.configureEach {
                resolutionStrategy.eachDependency { details ->
                    if (details.requested.group == 'old') {
                        details.useTarget(group: 'replacement', name: 'library', version: details.requested.version)
                    }
                }
            }
            """);
        rootProject.propertiesFile("versions.props").setProperty("test:consumer", "1.0");
    }

    @Test
    void renamed_request_is_a_constraint_when_rule_keeps_requested_version(
            GradleInvoker gradle, RootProject rootProject) {
        gradle.withArgs("writeVersionsLocks").buildsSuccessfully();

        // The original request determines the replacement's version and must contribute to its constraint hash.
        rootProject
                .file("versions.lock")
                .assertThat()
                .content()
                .contains("replacement:library:1.0 (1 constraints: a1060f50)");
        assertThat(gradle.withArgs("why", "--dependency", "replacement:library").buildsSuccessfully())
                .output()
                .contains("replacement:library:1.0", "test:consumer -> 1.0");
    }

    @Test
    void original_module_pin_is_a_constraint_when_rule_keeps_requested_version(
            GradleInvoker gradle, RootProject rootProject) {
        rootProject.propertiesFile("versions.props").setProperty("old:library", "2.0");

        gradle.withArgs("writeVersionsLocks").buildsSuccessfully();

        rootProject.file("versions.lock").assertThat().content().contains("replacement:library:2.0 (2 constraints:");
        assertThat(gradle.withArgs("why", "--dependency", "replacement:library").buildsSuccessfully())
                .output()
                .contains("replacement:library:2.0", "test:consumer -> 1.0", "projects -> 2.0");
    }
}
