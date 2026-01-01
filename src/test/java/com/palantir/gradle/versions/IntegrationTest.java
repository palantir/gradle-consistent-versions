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

import com.google.common.base.Splitter;
import com.palantir.gradle.testing.junit.DisabledConfigurationCache;
import com.palantir.gradle.testing.junit.GradlePluginTests;
import com.palantir.gradle.testing.maven.MavenArtifact;
import com.palantir.gradle.testing.maven.MavenRepo;

/**
 * Base test class for integration tests with common setup and utilities.
 * This class provides a Maven repository and common configuration.
 */
@GradlePluginTests
@DisabledConfigurationCache
public class IntegrationTest {

    // Note: The old setup() method is no longer needed because:
    // - keepFiles and debug are enabled by default in the new framework
    // - settings.gradle is automatically created in the new framework

    /**
     * Publishes artifacts to the Maven repository using the dependency graph notation.
     * The old Nebula framework used a DependencyGraph notation like:
     * - "group:artifact:version" for simple artifacts
     * - "group:artifact:version -> group2:artifact2:version2" for artifacts with dependencies
     *
     * In the new framework, use MavenRepo directly in tests with the MavenArtifact API:
     * <pre>
     * repo.publish(
     *     MavenArtifact.of("group:artifact:version"),
     *     MavenArtifact.builder()
     *         .coordinate("group:artifact2:version")
     *         .addDependency("group:artifact:version")
     *         .build()
     * );
     * </pre>
     *
     * This helper method parses the DependencyGraph notation for compatibility with existing tests.
     */
    protected void publishToRepo(MavenRepo repo, String... graph) {
        for (String entry : graph) {
            if (entry.contains(" -> ")) {
                // Parse dependency notation: "artifact -> dependency1, dependency2"
                String[] parts = entry.split(" -> ", 2);
                String coordinate = parts[0].trim();
                Iterable<String> dependencies = Splitter.on(',').split(parts[1]);

                MavenArtifact.Builder builder = MavenArtifact.builder().coordinate(coordinate);
                for (String dep : dependencies) {
                    builder.addDependency(dep.trim());
                }
                repo.publish(builder.build());
            } else {
                // Simple artifact with no dependencies
                repo.publish(MavenArtifact.of(entry.trim()));
            }
        }
    }

    /**
     * Note: Configuration cache testing is now handled automatically by the framework
     * when configuration cache is enabled in the build configuration.
     *
     * The framework automatically:
     * 1. Runs the build with --configuration-cache and verifies cache is stored
     * 2. Runs again with --configuration-cache --dry-run and verifies cache is reused
     *
     * Tests that are incompatible with configuration cache should use:
     * @DisabledConfigurationCache(reason="explanation")
     *
     * See the testing guide for more details.
     */
}
