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
import com.palantir.gradle.testing.maven.MavenArtifact;
import com.palantir.gradle.testing.maven.MavenRepo;
import com.palantir.gradle.testing.project.RootProject;
import com.palantir.gradle.testing.project.SubProject;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * https://github.com/palantir/sls-packaging does some funky stuff when resolving inter-project dependencies for the
 * purposes of detecting published recommended product dependencies, so we want to make double sure that GCV doesn't
 * accidentally break it.
 */
@GradlePluginTests
@DisabledConfigurationCache
class SlsPackagingCompatibilityIntegrationTest {

    private static final String PLUGIN_NAME = "com.palantir.consistent-versions";

    @BeforeEach
    void setup(MavenRepo repo, RootProject rootProject) {
        repo.publish(MavenArtifact.of("org.slf4j:slf4j-api:1.7.24"));

        rootProject
                .buildGradle()
                .plugins()
                .add(PLUGIN_NAME)
                .addWithoutApply("com.palantir.sls-java-service-distribution");

        rootProject.buildGradle().append("""
            allprojects {
                repositories {
                    maven { url = uri('%s') }
                }
            }
            """, repo.path());
    }

    @SuppressWarnings("for-rollout:deprecation")
    @Test
    void can_consume_recommended_product_dependencies_project(
            GradleInvoker gradle, RootProject rootProject, SubProject api, SubProject service) {
        rootProject.propertiesFile("versions.props").appendProperty("org.slf4j:*", "1.7.24");

        rootProject.buildGradle().append("""
            allprojects {
                version = '1.0.0'
            }
            """);

        api.buildGradle().plugins().add("java").add("com.palantir.sls-recommended-dependencies");

        api.buildGradle().append("""
            dependencies {
                implementation 'org.slf4j:slf4j-api'
            }

            recommendedProductDependencies {
                productDependency {
                    productGroup = 'org'
                    productName = 'product'
                    minimumVersion = '1.1.0'
                    maximumVersion = '1.x.x'
                }
            }
            """);

        service.buildGradle().plugins().add("java").add("com.palantir.sls-java-service-distribution");

        service.buildGradle().append("""
            dependencies {
                // Gets picked up by the productDependenciesConfig which is runtimeClasspath
                implementation project(':api')
            }
            """);

        InvocationResult wroteLocks = gradle.withArgs("--write-locks").buildsSuccessfully();

        // Maybe this is a bit too much but for a fixed version of sls-packaging, we expect this to not change
        List<String> expectedTasks = List.of(
                ":api:compileRecommendedProductDependencies",
                ":api:processResources",
                ":service:mergeDiagnosticsJson",
                ":service:resolveProductDependencies",
                ":service:createManifest",
                ":api:classes",
                ":api:configureProductDependencies",
                ":api:jar",
                ":service:jar");

        for (String taskPath : expectedTasks) {
            assertThat(wroteLocks).task(taskPath).succeeded();
        }

        gradle.withArgs("createManifest", "verifyLocks").buildsSuccessfully();
    }
}
