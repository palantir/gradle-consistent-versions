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
import static org.assertj.core.api.Assertions.assertThat;

import com.palantir.gradle.testing.execution.GradleInvoker;
import com.palantir.gradle.testing.junit.AdditionallyRunWithGradle;
import com.palantir.gradle.testing.junit.DisabledConfigurationCache;
import com.palantir.gradle.testing.junit.GradlePluginTests;
import com.palantir.gradle.testing.maven.MavenArtifact;
import com.palantir.gradle.testing.maven.MavenRepo;
import com.palantir.gradle.testing.project.RootProject;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@GradlePluginTests
@AdditionallyRunWithGradle("7.6.4")
class DependencySubstitutionIntegrationTest {
    @BeforeEach
    void setup(RootProject rootProject, MavenRepo repo) {
        repo.publish(
                MavenArtifact.builder()
                        .coordinate("test:consumer:1.0")
                        .addDependency("old:library:1.0")
                        .build(),
                MavenArtifact.of("replacement:library:2.0"));

        rootProject.buildGradle().plugins().add("java").add("com.palantir.consistent-versions");
        rootProject.buildGradle().append("""
            repositories {
                maven { url uri('%s') }
            }
            dependencies {
                implementation 'old:library'
                implementation 'test:consumer:1.0'
            }
            configurations.configureEach {
                resolutionStrategy.dependencySubstitution {
                    it.substitute(it.module('old:library')).using(it.module('replacement:library:2.0'))
                }
            }
            """, repo.path());
    }

    @Test
    void substituted_exact_recommendation_does_not_affect_locks(GradleInvoker gradle, RootProject rootProject) {
        assertIrrelevantRecommendationDoesNotAffectLocks(gradle, rootProject, "old:library");
    }

    @Test
    void substituted_wildcard_recommendation_does_not_affect_locks(GradleInvoker gradle, RootProject rootProject) {
        assertIrrelevantRecommendationDoesNotAffectLocks(gradle, rootProject, "old:*");
    }

    private static void assertIrrelevantRecommendationDoesNotAffectLocks(
            GradleInvoker gradle, RootProject rootProject, String recommendation) {
        rootProject.file("versions.props").overwrite("replacement:library = 1.9\n");
        gradle.withArgs("writeVersionsLocks").buildsSuccessfully();
        String originalLock = rootProject.file("versions.lock").text();
        assertThat(originalLock).contains("replacement:library:2.0").doesNotContain("old:library");

        // Adding, changing and removing an obsolete recommendation must all preserve the entire lock file.
        for (String oldRecommendation : List.of(recommendation + " = 1.0", recommendation + " = 1.1", "")) {
            rootProject.file("versions.props").overwrite("replacement:library = 1.9\n" + oldRecommendation + "\n");
            gradle.withArgs("verifyLocks").buildsSuccessfully();
            gradle.withArgs("writeVersionsLocks").buildsSuccessfully();
            assertThat(rootProject.file("versions.lock").text()).isEqualTo(originalLock);
        }
    }

    @Test
    @DisabledConfigurationCache(
            "Changing the lock file invalidates the configuration cache; why also requires it disabled")
    void replacement_recommendation_still_affects_locks(GradleInvoker gradle, RootProject rootProject) {
        rootProject.file("versions.props").overwrite("replacement:library = 1.9\n");
        gradle.withArgs("writeVersionsLocks").buildsSuccessfully();
        String originalLock = rootProject.file("versions.lock").text();
        assertThat(gradle.withArgs("why", "--dependency", "replacement:library").buildsSuccessfully())
                .output()
                .contains("projects -> 1.9")
                .doesNotContain("test:consumer -> 1.0");

        rootProject.file("versions.props").overwrite("replacement:library = 2.0\n");
        gradle.withArgs("verifyLocks").buildsWithFailure();
        gradle.withArgs("writeVersionsLocks").buildsSuccessfully();
        assertThat(rootProject.file("versions.lock").text())
                .contains("replacement:library:2.0")
                .isNotEqualTo(originalLock);
    }

    @Test
    @DisabledConfigurationCache(
            "Changing the lock file invalidates the configuration cache; why also requires it disabled")
    void version_only_substitution_preserves_requested_constraints(
            GradleInvoker gradle, RootProject rootProject, MavenRepo repo) {
        repo.publish(MavenArtifact.of("old:library:2.0"));
        rootProject.buildGradle().append("""
            configurations.configureEach {
                resolutionStrategy.dependencySubstitution {
                    it.substitute(it.module('old:library')).using(it.module('old:library:2.0'))
                }
            }
            """);

        rootProject.file("versions.props").overwrite("old:library = 1.0\n");
        gradle.withArgs("writeVersionsLocks").buildsSuccessfully();
        String originalLock = rootProject.file("versions.lock").text();

        rootProject.file("versions.props").overwrite("old:library = 1.1\n");
        gradle.withArgs("verifyLocks").buildsWithFailure();
        gradle.withArgs("writeVersionsLocks").buildsSuccessfully();
        assertThat(rootProject.file("versions.lock").text())
                .contains("old:library:2.0")
                .isNotEqualTo(originalLock);
        assertThat(gradle.withArgs("why", "--dependency", "old:library").buildsSuccessfully())
                .output()
                .contains("test:consumer -> 1.0", "projects -> 1.1");
    }
}
