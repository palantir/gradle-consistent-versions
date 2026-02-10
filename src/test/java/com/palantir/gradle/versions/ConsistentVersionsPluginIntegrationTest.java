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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.palantir.gradle.testing.execution.GradleInvoker;
import com.palantir.gradle.testing.execution.InvocationResult;
import com.palantir.gradle.testing.junit.DisabledConfigurationCache;
import com.palantir.gradle.testing.junit.GradlePluginTests;
import com.palantir.gradle.testing.maven.MavenArtifact;
import com.palantir.gradle.testing.maven.MavenRepo;
import com.palantir.gradle.testing.project.RootProject;
import com.palantir.gradle.testing.project.SubProject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@GradlePluginTests
class ConsistentVersionsPluginIntegrationTest {
    private static final String PLUGIN_NAME = "com.palantir.consistent-versions";

    @BeforeEach
    void setup(MavenRepo repo, RootProject rootProject) {
        repo.publish(
                MavenArtifact.builder()
                        .coordinate("ch.qos.logback:logback-classic:1.1.11")
                        .addDependency("org.slf4j:slf4j-api:1.7.22")
                        .build(),
                MavenArtifact.of("org.slf4j:slf4j-api:1.7.22"),
                MavenArtifact.of("org.slf4j:slf4j-api:1.7.25"),
                MavenArtifact.of("test-alignment:module-that-should-be-aligned-up:1.0"),
                MavenArtifact.of("test-alignment:module-that-should-be-aligned-up:1.1"),
                MavenArtifact.of("test-alignment:module-with-higher-version:1.1"));

        PomUtils.makePlatformPom(rootProject, repo, "org", "platform", "1.0");

        rootProject.buildGradle().plugins().add(PLUGIN_NAME);

        rootProject.buildGradle().append("""
            allprojects {
                tasks.register("resolveConfigurations", {
                    project.configurations.all { configuration ->
                        // Resolving these throws deprecation warnings for Gradle 8
                        def deprecatedResolve = configuration.name == "default" || configuration.name == "archives"
                        if (deprecatedResolve ||
                                (configuration.metaClass.respondsTo(configuration, "isCanBeResolved") &&
                                !configuration.isCanBeResolved())) {
                            return
                        }
                        configuration.resolve()
                    }
                })

                repositories {
                    maven { url uri("%s") }
                }
            }

            subprojects {
                // Parallel 'resolveConfigurations' sometimes breaks unless we force the root one to run first.
                tasks.named("resolveConfigurations", { it.mustRunAfter ":resolveConfigurations" })
            }
            """, repo.path());
    }

    @Test
    @DisabledConfigurationCache
    void can_write_locks_using_write_locks(GradleInvoker gradle, RootProject rootProject) {
        gradle.withArgs("--write-locks").buildsSuccessfully();

        rootProject.file("versions.lock").assertThat().exists();
        gradle.withArgs("resolveConfigurations").buildsSuccessfully();
    }

    @Test
    void can_write_locks_using_write_versions_locks(GradleInvoker gradle, RootProject rootProject) {
        gradle.withArgs("writeVersionsLocks").buildsSuccessfully();

        rootProject.file("versions.lock").assertThat().exists();
        gradle.withArgs("resolveConfigurations").buildsSuccessfully();
    }

    @Test
    void can_write_locks_using_abbreviated_write_versions_locks(GradleInvoker gradle, RootProject rootProject) {
        gradle.withArgs("wVL").buildsSuccessfully();

        rootProject.file("versions.lock").assertThat().exists();
        gradle.withArgs("resolveConfigurations").buildsSuccessfully();
    }

    @Test
    @DisabledConfigurationCache
    void locks_are_consistent_whether_or_not_we_do_write_locks_for_glob_forced_direct_dependency(
            GradleInvoker gradle, RootProject rootProject) {
        rootProject.buildGradle().plugins().add("java");

        rootProject.buildGradle().append("""
            dependencies {
                implementation 'org.slf4j:slf4j-api'
                runtimeOnly 'ch.qos.logback:logback-classic:1.1.11' // brings in slf4j-api 1.7.22
            }

            task resolve { doLast { configurations.runtimeClasspath.resolve() } }
            """);

        rootProject.propertiesFile("versions.props").appendProperty("org.slf4j:*", "1.7.25");

        gradle.withArgs("resolve", "--write-locks").buildsSuccessfully();
        gradle.withArgs("resolve").buildsSuccessfully();
    }

    @Test
    @DisabledConfigurationCache
    void get_version_function_works(GradleInvoker gradle, RootProject rootProject) {
        rootProject.buildGradle().plugins().add("java");

        rootProject.buildGradle().append("""
            dependencies {
                implementation 'org.slf4j:slf4j-api'
            }

            task demo {
                doLast { println "demo=" + getVersion('org.slf4j:slf4j-api', configurations.compileClasspath) }
            }
            """);

        // Pretend we have a lock file
        rootProject.file("versions.lock").createEmpty();

        rootProject.propertiesFile("versions.props").appendProperty("org.slf4j:*", "1.7.25");

        InvocationResult result = gradle.withArgs("demo").buildsSuccessfully();
        assertThat(result).output().contains("demo=1.7.25");
    }

    @Test
    @DisabledConfigurationCache
    void get_version_function_works_even_when_writing_locks(GradleInvoker gradle, RootProject rootProject) {
        rootProject.buildGradle().plugins().add("java");

        rootProject.buildGradle().append("""
            dependencies {
                implementation 'org.slf4j:slf4j-api'
            }

            task demo {
                doLast { println "demo=" + getVersion('org.slf4j:slf4j-api') }
            }
            """);

        rootProject.propertiesFile("versions.props").appendProperty("org.slf4j:*", "1.7.25");

        InvocationResult result = gradle.withArgs("demo", "--write-locks").buildsSuccessfully();
        assertThat(result).output().contains("demo=1.7.25");
    }

    @Test
    @DisabledConfigurationCache
    void virtual_platform_is_respected_across_projects(
            GradleInvoker gradle, RootProject rootProject, SubProject foo, SubProject bar) {
        foo.buildGradle().plugins().add("java");
        foo.buildGradle().append("""
            dependencies {
                implementation 'test-alignment:module-that-should-be-aligned-up:1.0'
            }
            """);

        bar.buildGradle().plugins().add("java");
        bar.buildGradle().append("""
            dependencies {
                implementation 'test-alignment:module-with-higher-version:1.1'
            }
            """);

        rootProject.propertiesFile("versions.props").append("""
            # Just to create a platform around test-alignment:*
            test-alignment:* = 1.0
            """);

        gradle.withArgs("--write-locks").buildsSuccessfully();

        String expectedLock = """
            # Run ./gradlew writeVersionsLocks to regenerate this file. Blank lines are to minimize merge conflicts.

            test-alignment:module-that-should-be-aligned-up:1.1 (1 constraints: a5041a2c)

            test-alignment:module-with-higher-version:1.1 (1 constraints: a6041b2c)
            """;

        assertThat(rootProject.file("versions.lock").text()).isEqualTo(expectedLock);
    }

    @Test
    @DisabledConfigurationCache
    void star_dependencies_in_the_absence_of_dependency_versions(
            GradleInvoker gradle, RootProject rootProject, SubProject foo) {
        foo.buildGradle().plugins().add("java");
        foo.buildGradle().append("""
            dependencies {
                implementation 'org.slf4j:slf4j-api'
            }
            """);

        rootProject.propertiesFile("versions.props").append("""
            org.slf4j:* = 1.7.25
            """);

        gradle.withArgs("--write-locks").buildsSuccessfully();

        String expectedLock = """
            # Run ./gradlew writeVersionsLocks to regenerate this file. Blank lines are to minimize merge conflicts.

            org.slf4j:slf4j-api:1.7.25 (1 constraints: 4105483b)
            """;

        assertThat(rootProject.file("versions.lock").text()).isEqualTo(expectedLock);

        // Ensure that this is a required constraint
        InvocationResult whyResult =
                gradle.withArgs("why", "--hash", "4105483b").buildsSuccessfully();
        assertThat(whyResult).output().contains("projects -> 1.7.25");
    }

    @Test
    @DisabledConfigurationCache
    void write_locks_and_verify_locks_work_in_the_presence_of_versions_props_constraints(
            GradleInvoker gradle, RootProject rootProject, SubProject foo, MavenRepo repo) {
        PomUtils.makePlatformPom(rootProject, repo, "org1", "platform", "1.0");
        PomUtils.makePlatformPom(rootProject, repo, "org2", "platform", "1.0");

        foo.buildGradle().plugins().add("java");

        foo.buildGradle().append("""
            configurations {
                other
            }

            dependencies {
                implementation 'org.slf4j:slf4j-api'

                rootConfiguration platform('org1:platform')
                rootConfiguration platform('org2:platform')
            }

            task resolveLockedConfigurations {
                doLast {
                    configurations.compileClasspath.resolve()
                    configurations.runtimeClasspath.resolve()
                }
            }

            // This is to ensure that the platform deps are successfully resolvable on a non-locked configuration
            task resolveNonLockedConfiguration {
                doLast {
                    configurations.other.resolve()
                }
            }
            """);

        rootProject.propertiesFile("versions.props").append("""
            org1:platform = 1.0
            org2:* = 1.0
            org.slf4j:slf4j-api = 1.7.25
            """);

        gradle.withArgs("--write-locks").buildsSuccessfully();

        String expectedLock = """
            # Run ./gradlew writeVersionsLocks to regenerate this file. Blank lines are to minimize merge conflicts.

            org.slf4j:slf4j-api:1.7.25 (1 constraints: 4105483b)

            org1:platform:1.0 (1 constraints: a5041a2c)

            org2:platform:1.0 (1 constraints: a5041a2c)
            """;

        assertThat(rootProject.file("versions.lock").text()).isEqualTo(expectedLock);

        // Ensure you can verify locks and resolve the actual locked configurations
        gradle.withArgs("verifyLocks", "resolveLockedConfigurations", "resolveNonLockedConfiguration")
                .buildsSuccessfully();
    }

    @Test
    @DisabledConfigurationCache
    void versions_props_contents_do_not_get_published_as_constraints(
            GradleInvoker gradle, RootProject rootProject, SubProject foo) throws IOException {
        foo.buildGradle().plugins().add("java").add("maven-publish");

        foo.buildGradle().append("""
            publishing.publications {
                maven(MavenPublication) {
                    from components.java
                }
            }

            dependencies {
                implementation 'ch.qos.logback:logback-classic'
            }
            """);

        rootProject.propertiesFile("versions.props").append("""
            org.slf4j:* = 1.7.25
            ch.qos.logback:* = 1.1.11
            should:not-publish = 1.0
            """);

        gradle.withArgs("--write-locks", "generateMetadataFileForMavenPublication")
                .buildsSuccessfully();

        MetadataFile.Dependency logbackDep =
                new MetadataFile.Dependency("ch.qos.logback", "logback-classic", Map.of("requires", "1.1.11"));
        MetadataFile.Dependency slf4jDep =
                new MetadataFile.Dependency("org.slf4j", "slf4j-api", Map.of("requires", "1.7.25"));

        // foo's metadata file has the right dependency constraints
        Path fooMetadataFilename = foo.buildDir().path().resolve("publications/maven/module.json");
        MetadataFile fooMetadata = new ObjectMapper().readValue(fooMetadataFilename.toFile(), MetadataFile.class);

        assertThat(fooMetadata.variants())
                .containsExactlyInAnyOrder(
                        new MetadataFile.Variant("apiElements", null, Set.of(logbackDep, slf4jDep)),
                        new MetadataFile.Variant("runtimeElements", Set.of(logbackDep), Set.of(logbackDep, slf4jDep)));
    }

    @Test
    @DisabledConfigurationCache
    void intransitive_dependency_on_published_configuration_should_not_break_realizing_it_later(
            GradleInvoker gradle, RootProject rootProject, SubProject source, SubProject target) {
        source.buildGradle().append("""
            configurations {
                transitive
                intransitive
            }
            dependencies {
                // This wrecks us
                intransitive project(':target'), { transitive = false }

                transitive project(':target')
            }

            task resolveIntransitively {
                doLast {
                    configurations.intransitive.resolvedConfiguration
                }
            }
            task resolveTransitively {
                mustRunAfter resolveIntransitively
                doLast {
                    configurations.transitive.resolvedConfiguration
                }
            }
            """);

        target.buildGradle().plugins().add("java");

        target.buildGradle().append("""
            dependencies {
                // Test the lazy action on published configurations like apiElements, runtimeElements
                // that copies over platform dependencies from rootConfiguration.
                rootConfiguration platform("org:platform")
            }
            """);

        rootProject.propertiesFile("versions.props").append("""
            org:platform = 1.0
            """);

        // This is just for debugging
        rootProject.buildGradle().append("""
            allprojects {
                configurations.all {
                    incoming.beforeResolve {
                        println "Resolving: $it"
                    }
                }
            }
            """);

        gradle.withArgs("--write-locks").buildsSuccessfully();

        gradle.withArgs("resolveIntransitively", "resolveTransitively").buildsSuccessfully();
    }

    @Test
    @SuppressWarnings("MethodLength")
    @DisabledConfigurationCache
    void works_with_included_builds(GradleInvoker gradle, RootProject rootProject, MavenRepo repo) {
        // add included build
        Path includedBuild = rootProject.directory("included-build").path();
        rootProject.settingsGradle().append("""
            includeBuild 'included-build'
            """);

        // configure the included build
        rootProject
                .directory(includedBuild.toString())
                .gradleFile("settings.gradle")
                .append("""
                    rootProject.name = 'included-build'

                    include 'innerA'
                    include 'innerB'
                    """);

        rootProject
                .directory(includedBuild.toString())
                .gradleFile("build.gradle")
                .plugins()
                .add(PLUGIN_NAME);

        rootProject
                .directory(includedBuild.toString())
                .gradleFile("build.gradle")
                .append("""
                    allprojects {
                        group 'com.palantir.included-build'
                        version '1.0.0'

                        repositories {
                            maven { url uri("%s") }
                        }
                    }
                    """, repo.path());

        Path innerA = includedBuild.resolve("innerA");
        rootProject
                .directory(innerA.toString())
                .gradleFile("build.gradle")
                .append("""
                    dependencies {
                        implementation 'org.slf4j:slf4j-api'
                        runtimeOnly 'ch.qos.logback:logback-classic:1.1.11' // brings in slf4j-api 1.7.22
                    }

                    publishing {
                        repositories {
                            maven {
                                url = "%s"
                            }
                        }

                        publications {
                            maven(MavenPublication) {
                                from components.java
                            }
                        }
                    }
                    """, repo.path())
                .plugins()
                .add("java")
                .add("maven-publish");

        Path innerB = includedBuild.resolve("innerB");
        rootProject
                .directory(innerB.toString())
                .gradleFile("build.gradle")
                .append("""
                    publishing {
                        repositories {
                            maven {
                                url = "%s"
                            }
                        }

                        publications {
                            maven(MavenPublication) {
                                from components.java
                            }
                        }
                    }
                    dependencies {
                        implementation 'test-alignment:module-with-higher-version'
                    }
                    """, repo.path())
                .plugins()
                .add("java")
                .add("maven-publish");

        rootProject
                .directory(includedBuild.toString())
                .propertiesFile("versions.props")
                .append("""
                    org.slf4j:slf4j-api = 1.7.25
                    test-alignment:* = 1.1
                    """);

        // configure main build
        rootProject.buildGradle().plugins().add("java");
        rootProject.buildGradle().plugins().add("maven-publish");

        rootProject.buildGradle().append("""
            group 'com.palantir.main-build'
            version '1.2.3'

            publishing {
                repositories {
                    maven {
                        url = "%s"
                    }
                }

                publications {
                    maven(MavenPublication) {
                        from components.java
                    }
                }
            }

            dependencies {
                implementation 'test-alignment:module-that-should-be-aligned-up'
            }
            """, repo.path());

        rootProject.propertiesFile("versions.props").append("""
            test-alignment:* = 1.0
            """);

        gradle.withArgs("--write-locks").buildsSuccessfully();

        // inner versions lock is expected
        String expectedInnerLock = """
            # Run ./gradlew writeVersionsLocks to regenerate this file. Blank lines are to minimize merge conflicts.

            ch.qos.logback:logback-classic:1.1.11 (1 constraints: 36052a3b)

            org.slf4j:slf4j-api:1.7.25 (2 constraints: 7d12a137)

            test-alignment:module-with-higher-version:1.1 (1 constraints: a6041b2c)
            """;

        assertThat(rootProject
                        .directory(includedBuild.toString())
                        .file("versions.lock")
                        .text())
                .isEqualTo(expectedInnerLock);

        // root build: versions lock is expected
        String expectedRootLock = """
            # Run ./gradlew writeVersionsLocks to regenerate this file. Blank lines are to minimize merge conflicts.

            test-alignment:module-that-should-be-aligned-up:1.0 (1 constraints: a5041a2c)
            """;

        assertThat(rootProject.file("versions.lock").text()).isEqualTo(expectedRootLock);

        // we add a dependencies on the inner build
        rootProject.buildGradle().append("""
            dependencies {
              implementation 'com.palantir.included-build:innerA'
              implementation 'com.palantir.included-build:innerB'
            }
            """);

        // build succeeds
        gradle.withArgs("--write-locks").buildsSuccessfully();

        gradle.withArgs(
                        ":publishMavenPublicationToMavenRepository",
                        ":included-build:innerA:publishMavenPublicationToMavenRepository",
                        ":included-build:innerB:publishMavenPublicationToMavenRepository")
                .buildsSuccessfully();

        // root build: dependency is bumped - there is a difference in resolution between Gradle versions hence why we
        // do not compare contents directly
        assertThat(rootProject.file("versions.lock").text())
                .contains("ch.qos.logback:logback-classic:1.1.11 (1 constraints: 36052a3b)")
                .contains("test-alignment:module-that-should-be-aligned-up:1.1 (1 constraints: a5041a2c)")
                .contains("test-alignment:module-with-higher-version:1.1 (1 constraints: a6041b2c)");
    }
}
