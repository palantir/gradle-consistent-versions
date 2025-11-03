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

import static org.assertj.core.api.Assertions.assertThat;

import com.palantir.gradle.testing.execution.GradleInvoker;
import com.palantir.gradle.testing.execution.InvocationResult;
import com.palantir.gradle.testing.junit.GradlePluginTests;
import com.palantir.gradle.testing.project.RootProject;
import com.palantir.gradle.testing.project.SubProject;
import java.io.File;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIf;

/**
 * https://github.com/palantir/sls-packaging does some funky stuff when resolving inter-project dependencies for the
 * purposes of detecting published recommended product dependencies, so we want to make double sure that GCV doesn't
 * accidentally break it.
 */
@GradlePluginTests
class SlsPackagingCompatibilityIntegrationSpec {

    private static final String PLUGIN_NAME = "com.palantir.consistent-versions";
    private File mavenRepo;

    @BeforeEach
    void setup(RootProject rootProject) {
        mavenRepo = MavenRepoUtils.generateMavenRepo(rootProject.path().resolve("build"), "org.slf4j:slf4j-api:1.7.24");
        rootProject.buildGradle().append("""
            buildscript {
                repositories {
                    mavenCentral()
                }
            }
            plugins {
                id '%s'
                id 'com.palantir.sls-java-service-distribution' version '7.31.0' apply false
            }
            allprojects {
                repositories {
                    maven { url "file:///%s" }
                }
            }
            """, PLUGIN_NAME, mavenRepo.getAbsolutePath());
    }

    @DisabledIf("isGradle9OrLater")
    @Test
    void can_consume_recommended_product_dependencies_project(
            GradleInvoker gradle, RootProject rootProject, SubProject apiProject, SubProject serviceProject) {
        rootProject.file("versions.props").overwrite("""
            org.slf4j:* = 1.7.24
            """);

        rootProject.buildGradle().append("""
            allprojects {
                version = '1.0.0'
            }
            """);

        rootProject.settingsGradle().include("api");
        rootProject.settingsGradle().include("service");
        apiProject.buildGradle().append("""
            apply plugin: 'java'
            apply plugin: 'com.palantir.sls-recommended-dependencies'
            \s\s\s\s
            dependencies {
                implementation 'org.slf4j:slf4j-api'
            }
            \s\s\s\s
            recommendedProductDependencies {
                productDependency {
                    productGroup = 'org'
                    productName = 'product'
                    minimumVersion = '1.1.0'
                    maximumVersion = '1.x.x'
                }
            }
            """);

        serviceProject.buildGradle().append("""
            apply plugin: 'java'
            apply plugin: 'com.palantir.sls-java-service-distribution'
            \s\s\s\s
            dependencies {
                // Gets picked up by the productDependenciesConfig which is runtimeClasspath
                implementation project(':api')
            }
            """);

        InvocationResult wroteLocks = gradle.withArgs("--write-locks").buildsSuccessfully();
        // Maybe this is a bit too much but for a fixed version of sls-packaging, we expect this to not change
        Set<String> successfulTasks = wroteLocks
                .output()
                .lines()
                .filter(line -> line.contains("> Task :"))
                .map(line -> line.substring(line.indexOf(":"), line.length()).trim())
                .collect(Collectors.toSet());

        assertThat(successfulTasks)
                .containsExactlyInAnyOrder(
                        ":api:compileRecommendedProductDependencies",
                        ":api:processResources",
                        ":service:mergeDiagnosticsJson",
                        ":service:resolveProductDependencies",
                        ":service:createManifest",
                        ":api:classes",
                        ":api:configureProductDependencies",
                        ":api:jar",
                        ":service:jar");

        gradle.withArgs("createManifest", "verifyLocks").buildsSuccessfully();
    }

    @SuppressWarnings("unused")
    private static boolean isGradle9OrLater() {
        String gradleVersion = org.gradle.util.GradleVersion.current().getVersion();
        return gradleVersion.startsWith("9");
    }
}
