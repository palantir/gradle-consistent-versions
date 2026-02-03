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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.palantir.gradle.testing.execution.GradleInvoker;
import com.palantir.gradle.testing.execution.InvocationResult;
import com.palantir.gradle.testing.junit.DisabledConfigurationCache;
import com.palantir.gradle.testing.junit.GradlePluginTests;
import com.palantir.gradle.testing.maven.MavenArtifact;
import com.palantir.gradle.testing.maven.MavenRepo;
import com.palantir.gradle.testing.project.GradleProject;
import com.palantir.gradle.testing.project.RootProject;
import com.palantir.gradle.testing.project.SubProject;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.immutables.value.Value;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@GradlePluginTests
@DisabledConfigurationCache
class VersionsPropsPluginIntegrationTest {
    private static final String PLUGIN_NAME = "com.palantir.versions-props";

    @BeforeEach
    void setup(MavenRepo repo, RootProject rootProject) {
        repo.publish(
                MavenArtifact.builder()
                        .coordinate("ch.qos.logback:logback-classic:1.2.3")
                        .addDependency("org.slf4j:slf4j-api:1.7.25")
                        .build(),
                MavenArtifact.builder()
                        .coordinate("ch.qos.logback:logback-classic:1.1.11")
                        .addDependency("org.slf4j:slf4j-api:1.7.22")
                        .build(),
                MavenArtifact.of("org.slf4j:slf4j-api:1.7.21"),
                MavenArtifact.of("org.slf4j:slf4j-api:1.7.22"),
                MavenArtifact.of("org.slf4j:slf4j-api:1.7.24"),
                MavenArtifact.of("org.slf4j:slf4j-api:1.7.25"),
                MavenArtifact.builder()
                        .coordinate("com.fasterxml.jackson.core:jackson-databind:2.9.0")
                        .addDependency("com.fasterxml.jackson.core:jackson-annotations:2.9.0")
                        .build(),
                MavenArtifact.of("com.fasterxml.jackson.core:jackson-annotations:2.9.0"),
                MavenArtifact.of("com.fasterxml.jackson.core:jackson-annotations:2.9.7"),
                MavenArtifact.of("com.fasterxml.jackson.core:jackson-databind:2.9.7"));

        makePlatformPom(rootProject, repo, "org", "platform", "1.0");

        rootProject.buildGradle().plugins().add(PLUGIN_NAME);
        rootProject.buildGradle().append("""
            buildscript {
                repositories {
                    mavenCentral()
                }
            }

            allprojects {
                repositories {
                    maven { url "file:///%s" }
                }
            }

            // Make it easy to verify what versions of dependencies you got.
            allprojects {
                configurations.matching { it.name == 'runtimeClasspath' }.all {
                    resolutionStrategy.activateDependencyLocking()
                }
                task resolveConfigurations {
                    doLast {
                        if (pluginManager.hasPlugin('java')) {
                            configurations.compileClasspath.resolve()
                            configurations.runtimeClasspath.resolve()
                        }
                    }
                }
            }
            """, repo.path());
    }

    @Test
    void star_dependency_constraint_is_injected_for_direct_dependency(
            GradleInvoker gradle, RootProject rootProject, SubProject foo) {
        rootProject.propertiesFile("versions.props").appendProperty("org.slf4j:*", "1.7.24");

        foo.buildGradle().plugins().add("java");

        foo.buildGradle().append("""
            dependencies {
                implementation 'org.slf4j:slf4j-api'
            }
            """);

        gradle.withArgs("resolveConfigurations", "--write-locks").buildsSuccessfully();

        verifyLockfile(foo, "org.slf4j:slf4j-api:1.7.24");
    }

    @Test
    void star_dependency_constraint_is_not_forcefully_downgraded_for_transitive_dependency(
            GradleInvoker gradle, RootProject rootProject, SubProject foo) {
        rootProject.propertiesFile("versions.props").appendProperty("org.slf4j:*", "1.7.21");

        rootProject
                .propertiesFile("versions.props")
                .appendProperty("ch.qos.logback:logback-classic", "1.1.11"); // brings in slf4j-api 1.7.22

        foo.buildGradle().plugins().add("java");

        foo.buildGradle().append("""
            dependencies {
                implementation 'ch.qos.logback:logback-classic'
            }
            """);

        gradle.withArgs("resolveConfigurations", "--write-locks").buildsSuccessfully();

        verifyLockfile(foo, "org.slf4j:slf4j-api:1.7.22");
    }

    @Test
    void star_dependency_constraint_upgrades_transitive_dependency(
            GradleInvoker gradle, RootProject rootProject, SubProject foo) {
        rootProject.propertiesFile("versions.props").appendProperty("org.slf4j:*", "1.7.25");

        rootProject
                .propertiesFile("versions.props")
                .appendProperty("ch.qos.logback:logback-classic", "1.1.11"); // brings in slf4j-api 1.7.22

        foo.buildGradle().plugins().add("java");

        foo.buildGradle().append("""
            dependencies {
                implementation 'ch.qos.logback:logback-classic'
            }
            """);

        gradle.withArgs("resolveConfigurations", "--write-locks").buildsSuccessfully();

        verifyLockfile(foo, "org.slf4j:slf4j-api:1.7.25");
    }

    @Test
    void imported_platform_generated_correctly_in_pom(GradleInvoker gradle, RootProject rootProject, SubProject foo)
            throws IOException {
        rootProject.propertiesFile("versions.props").appendProperty("org:platform", "1.0");

        rootProject
                .propertiesFile("versions.props")
                .appendProperty("other:constraint", "1.0.0"); // This shouldn't end up in the POM

        foo.buildGradle().plugins().add("java-library").add("maven-publish");

        foo.buildGradle().append("""
            dependencies {
                rootConfiguration platform('org:platform')
            }
            publishing {
                publications {
                    main(MavenPublication) {
                        from components.java
                    }
                }
            }
            """);

        gradle.withArgs("foo:generatePomFile").buildsSuccessfully();

        foo.file("build/publications/main/pom-default.xml").assertThat().exists();

        XmlMapper xmlMapper = new XmlMapper();
        Pom pom = xmlMapper.readValue(
                foo.file("build/publications/main/pom-default.xml").path().toFile(), Pom.class);

        Set<Map<String, String>> actualDependencies = pom.dependencyManagement().dependencies().stream()
                .map(dep -> Map.of(
                        "groupId", dep.groupId(),
                        "artifactId", dep.artifactId(),
                        "version", dep.version(),
                        "scope", dep.scope(),
                        "type", dep.type()))
                .collect(Collectors.toSet());

        Set<Map<String, String>> expectedDependencies = Set.of(Map.of(
                "groupId", "org",
                "artifactId", "platform",
                "version", "1.0",
                "scope", "import",
                "type", "pom"));

        assertThat(actualDependencies).containsExactlyInAnyOrderElementsOf(expectedDependencies);
    }

    @Test
    void non_glob_module_forces_do_not_get_added_to_a_matching_platform_too(
            GradleInvoker gradle, RootProject rootProject) {
        rootProject.buildGradle().plugins().add("java");

        rootProject.buildGradle().append("""
            dependencies {
                implementation 'com.fasterxml.jackson.core:jackson-databind'
            }
            """);

        rootProject
                .propertiesFile("versions.props")
                .appendProperty("com.fasterxml.jackson.core:jackson-databind", "2.9.0")
                .appendProperty("com.fasterxml.jackson.*:*", "2.9.7");

        gradle.withArgs("resolveConfigurations", "--write-locks").buildsSuccessfully();

        verifyLockfile(
                rootProject,
                "com.fasterxml.jackson.core:jackson-databind:2.9.0",
                "com.fasterxml.jackson.core:jackson-annotations:2.9.7");
    }

    @Test
    void throws_if_resolving_configuration_in_afterevaluate(GradleInvoker gradle, RootProject rootProject) {
        rootProject.buildGradle().append("""
            configurations { foo }

            afterEvaluate {
                configurations.foo.resolve()
            }
            """);

        rootProject.propertiesFile("versions.props").createEmpty();

        InvocationResult result = gradle.withArgs().buildsWithFailure();

        assertThat(result).output().contains("Not allowed to resolve");
    }

    @Test
    void does_not_throw_if_excluded_configuration_is_resolved_early(GradleInvoker gradle, RootProject rootProject) {
        rootProject.buildGradle().append("""
            configurations { foo }

            versionRecommendations {
                excludeConfigurations 'foo'
            }

            afterEvaluate {
                configurations.foo.resolve()
            }
            """);

        rootProject.propertiesFile("versions.props").createEmpty();

        gradle.withArgs().buildsSuccessfully();
    }

    @Test
    void creates_rootconfiguration_even_if_versions_props_file_missing(GradleInvoker gradle, RootProject rootProject)
            throws IOException {
        rootProject.buildGradle().append("""
            dependencies {
                constraints {
                    rootConfiguration 'org.slf4j:slf4j-api:1.7.25'
                }
            }
            """);

        Files.deleteIfExists(rootProject.file("versions.props").path());

        gradle.withArgs().buildsSuccessfully();
    }

    @Test
    void build_succeeds_without_versions_props_or_versions_lock(GradleInvoker gradle, SubProject foo) {
        foo.buildGradle().plugins().add("java");

        gradle.withArgs("build").buildsSuccessfully();
    }

    private void verifyLockfile(GradleProject project, String... lines) {
        String lockfile = project.file("gradle.lockfile").text();
        for (String line : lines) {
            assertThat(lockfile).contains(line + "=runtimeClasspath");
        }
    }

    static void makePlatformPom(RootProject rootProject, MavenRepo repo, String group, String name, String version) {
        rootProject
                .directory(repo.path()
                        .resolve(group)
                        .resolve(name)
                        .resolve(version)
                        .toString())
                .file("platform-1.0.pom")
                .overwrite("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <project xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd" xmlns="http://maven.apache.org/POM/4.0.0"
                        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                      <modelVersion>4.0.0</modelVersion>
                      <packaging>pom</packaging>
                      <groupId>%s</groupId>
                      <artifactId>%s</artifactId>
                      <version>%s</version>
                      <dependencyManagement>
                        <dependencies>
                        </dependencies>
                      </dependencyManagement>
                    </project>
                    """, group, name, version);
    }

    @Value.Immutable
    @JsonDeserialize(as = ImmutablePom.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    interface Pom {
        @JacksonXmlProperty(localName = "dependencyManagement")
        DependencyManagement dependencyManagement();
    }

    @Value.Immutable
    @JsonDeserialize(as = ImmutableDependencyManagement.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    interface DependencyManagement {
        @JacksonXmlProperty(localName = "dependency")
        @JacksonXmlElementWrapper(localName = "dependencies")
        List<Dependency> dependencies();
    }

    @Value.Immutable
    @JsonDeserialize(as = ImmutableDependency.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    interface Dependency {
        String groupId();

        String artifactId();

        String version();

        String scope();

        String type();
    }
}
