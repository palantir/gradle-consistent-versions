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
import com.palantir.gradle.testing.project.GradleProject;
import com.palantir.gradle.testing.project.RootProject;
import com.palantir.gradle.testing.project.SubProject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@GradlePluginTests
@DisabledConfigurationCache
class VersionsLockPluginIntegrationTest {
    private static final String PLUGIN_NAME = "com.palantir.versions-lock";

    @BeforeEach
    void setup(MavenRepo repo, RootProject rootProject) {
        repo.publish(
                MavenArtifact.builder()
                        .coordinate("ch.qos.logback:logback-classic:1.2.3")
                        .addDependency("org.slf4j:slf4j-api:1.7.25")
                        .build(),
                MavenArtifact.of("org.slf4j:slf4j-api:1.7.11"),
                MavenArtifact.of("org.slf4j:slf4j-api:1.7.20"),
                MavenArtifact.of("org.slf4j:slf4j-api:1.7.24"),
                MavenArtifact.of("org.slf4j:slf4j-api:1.7.25"),
                MavenArtifact.of("junit:junit:4.10"),
                MavenArtifact.builder()
                        .coordinate("org:test-dep-that-logs:1.0")
                        .addDependency("org.slf4j:slf4j-api:1.7.11")
                        .build(),
                MavenArtifact.of("org:another-transitive-dependency:3.2.1"),
                MavenArtifact.builder()
                        .coordinate("org:another-direct-dependency:1.2.3")
                        .addDependency("org:another-transitive-dependency:3.2.1")
                        .build());

        PomUtils.makePlatformPom(rootProject, repo, "org", "platform", "1.0");

        rootProject.buildGradle().append("""
            buildscript {
                repositories {
                    mavenCentral()
                }
            }
            """);

        rootProject.buildGradle().plugins().add(PLUGIN_NAME);

        rootProject.buildGradle().append("""
            allprojects {
                repositories {
                    maven { url = uri('%s') }
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

    private void standardSetup(RootProject rootProject, SubProject foo, SubProject bar, SubProject forced) {
        foo.buildGradle().plugins().add("java");
        foo.buildGradle().append("""
            dependencies {
                implementation 'org.slf4j:slf4j-api:1.7.24'
            }
            """);

        bar.buildGradle().plugins().add("java");
        bar.buildGradle().append("""
            dependencies {
                implementation "org.slf4j:slf4j-api:${project.bar_version}"
            }
            """);
        rootProject.gradlePropertiesFile().appendProperty("bar_version", "1.7.11");

        forced.buildGradle().plugins().add("java");
        forced.buildGradle().append("""
            dependencies {
                implementation "org.slf4j:slf4j-api"
            }
            configurations.all {
                resolutionStrategy {
                    force "org.slf4j:slf4j-api:1.7.20"
                }
            }
            """);
    }

    @Test
    void can_write_locks(GradleInvoker gradle, RootProject rootProject) {
        gradle.withArgs("--write-locks").buildsSuccessfully();

        rootProject.file("versions.lock").assertThat().exists();
    }

    @Test
    void cannot_resolve_without_a_root_lock_file(
            GradleInvoker gradle, RootProject rootProject, SubProject foo, SubProject bar, SubProject forced) {
        standardSetup(rootProject, foo, bar, forced);

        InvocationResult result = gradle.withArgs("resolveConfigurations").buildsWithFailure();

        assertThat(result.output().lines())
                .anyMatch(line -> line.matches(".*Root lock file '([^']+)' doesn't exist, please run.*"));
    }

    @Test
    void can_resolve_without_a_root_lock_file_if_lock_file_is_ignored(
            GradleInvoker gradle, RootProject rootProject, SubProject foo, SubProject bar, SubProject forced) {
        standardSetup(rootProject, foo, bar, forced);

        gradle.withArgs("resolveConfigurations", "-PignoreLockFile").buildsSuccessfully();
    }

    @Test
    void consolidates_subproject_dependencies(
            GradleInvoker gradle, RootProject rootProject, SubProject foo, SubProject bar, SubProject forced) {
        String expectedError = "Locked by versions.lock";

        standardSetup(rootProject, foo, bar, forced);

        rootProject.buildGradle().append("""
            subprojects {
                configurations.matching { it.name == 'runtimeClasspath' }.all {
                    resolutionStrategy.activateDependencyLocking()
                }
            }
            """);

        // when: "I write locks"
        gradle.withArgs("resolveConfigurations", "--write-locks").buildsSuccessfully();

        // then: "Lock files are consistent with version resolved at root"
        assertThat(rootProject.file("versions.lock").text().lines())
                .anyMatch(line -> line.startsWith("org.slf4j:slf4j-api:1.7.24"));

        verifyLockfile(foo, "org.slf4j:slf4j-api:1.7.24");
        verifyLockfile(bar, "org.slf4j:slf4j-api:1.7.24");

        // then: "Manually forced version overrides unified dependency"
        verifyLockfile(forced, "org.slf4j:slf4j-api:1.7.20");

        // then: "I can resolve configurations"
        gradle.withArgs("resolveConfigurations").buildsSuccessfully();

        // when: "I make bar's version constraint incompatible with the force"
        InvocationResult incompatible =
                gradle.withArgs("-Pbar_version=1.7.25", "resolveConfigurations").buildsWithFailure();

        // then: "Resolution fails"
        assertThat(incompatible).output().contains(expectedError);
    }

    @Test
    void works_on_just_root_project(GradleInvoker gradle, RootProject rootProject) {
        rootProject.buildGradle().plugins().add("java");
        rootProject.buildGradle().append("""
            dependencies {
                implementation 'ch.qos.logback:logback-classic:1.2.3' // brings in slf4j-api 1.7.25
            }
            """);

        gradle.withArgs("--write-locks").buildsSuccessfully();

        List<String> lines = rootProject.file("versions.lock").text().lines().toList();
        assertThat(lines).contains("ch.qos.logback:logback-classic:1.2.3 (1 constraints: 0805f935)");
        assertThat(lines).contains("org.slf4j:slf4j-api:1.7.25 (1 constraints: 400d4d2a)");
    }

    @Test
    void get_a_conflict_even_if_no_lock_files_applied(
            GradleInvoker gradle, RootProject rootProject, SubProject foo, SubProject bar, SubProject forced) {
        String expectedError = "Locked by versions.lock";

        standardSetup(rootProject, foo, bar, forced);

        // when: "I write locks"
        gradle.withArgs("--write-locks").buildsSuccessfully();

        // then: "Root lock file has expected resolution result"
        assertThat(rootProject.file("versions.lock").text().lines())
                .anyMatch(line -> line.contains("org.slf4j:slf4j-api:1.7.24"));

        // then: "I can resolve configurations"
        gradle.withArgs("resolveConfigurations").buildsSuccessfully();

        // when: "I make bar's version constraint incompatible with the force"
        InvocationResult incompatible =
                gradle.withArgs("-Pbar_version=1.7.25", "resolveConfigurations").buildsWithFailure();

        // then: "Resolution fails"
        assertThat(incompatible).output().contains(expectedError);
    }

    @Test
    void fails_fast_when_subproject_that_is_depended_on_has_same_name_as_root_project(
            GradleInvoker gradle, RootProject rootProject, SubProject foobar, SubProject other) {
        String expectedError =
                "This plugin doesn't work if the root project shares both group and name with a subproject";

        rootProject.buildGradle().append("""
            allprojects {
                group 'same'
            }
            """);

        rootProject.settingsGradle().rootProjectName("foobar");

        foobar.buildGradle().plugins().add("java-library");

        other.buildGradle().plugins().add("java-library");
        other.buildGradle().append("""
            dependencies {
                implementation project(':foobar')
            }
            """);

        // Otherwise the lack of a lock file will throw first
        rootProject.file("versions.lock").createEmpty();

        InvocationResult error = gradle.withArgs().buildsWithFailure();
        assertThat(error).output().contains(expectedError);
    }

    @Test
    void fails_fast_when_multiple_subprojects_share_the_same_coordinate(GradleInvoker gradle, RootProject rootProject) {
        String expectedError = "All subprojects must have unique $group:$name";

        rootProject.buildGradle().append("""
            allprojects {
                group 'same'
            }
            """);

        // both projects will have name = 'a'
        rootProject.subproject("foo").subproject("a");
        rootProject.subproject("bar").subproject("a");

        // Otherwise the lack of a lock file will throw first
        rootProject.file("versions.lock").createEmpty();

        InvocationResult error = gradle.withArgs().buildsWithFailure();
        assertThat(error).output().contains(expectedError);
    }

    @Test
    void detects_failonversionconflict_on_locked_configuration(GradleInvoker gradle, RootProject rootProject) {
        rootProject.buildGradle().plugins().add("java");
        rootProject.buildGradle().append("""
            configurations.compileClasspath.resolutionStrategy.failOnVersionConflict()
            """);

        rootProject.file("versions.lock").createEmpty();

        InvocationResult failure = gradle.withArgs().buildsWithFailure();
        assertThat(failure).output().contains("Must not use failOnVersionConflict");
    }

    @Test
    void ignores_failonversionconflict_on_non_locked_configuration(GradleInvoker gradle, RootProject rootProject) {
        rootProject.buildGradle().plugins().add("java");
        rootProject.buildGradle().append("""
            configurations {
                foo {
                    resolutionStrategy.failOnVersionConflict()
                }
            }
            """);

        rootProject.file("versions.lock").createEmpty();

        gradle.withArgs().buildsSuccessfully();
    }

    @Test
    void fails_if_new_dependency_added_that_was_not_in_the_lock_file(
            MavenRepo repo, GradleInvoker gradle, RootProject _rootProject, SubProject foo) {
        String expectedError = "Found dependencies that were not in the lock state";

        repo.publish(MavenArtifact.of("org:a:1.0"), MavenArtifact.of("org:b:1.0"));

        foo.buildGradle().plugins().add("java");

        foo.buildGradle().append("""
            dependencies {
                implementation 'org:a:1.0'
            }
            """);

        gradle.withArgs("--write-locks").buildsSuccessfully();

        foo.buildGradle().append("""
            dependencies {
                implementation 'org:b:1.0'
            }
            """);

        // then: 'Check fails because locks are not up to date'
        InvocationResult failure = gradle.withArgs(":check").buildsWithFailure();
        assertThat(failure).task(":verifyLocks").failed();
        assertThat(failure).output().contains(expectedError);

        // and: 'Can finally write locks once again'
        gradle.withArgs("--write-locks").buildsSuccessfully();
        gradle.withArgs("verifyLocks").buildsSuccessfully();
    }

    @Test
    void does_not_fail_if_unifiedclasspath_is_unresolvable(
            GradleInvoker gradle, RootProject rootProject, SubProject foo) {
        rootProject.file("versions.lock").overwrite("""
            org.slf4j:slf4j-api:1.7.11 (0 constraints: 0000000)
            """);

        foo.buildGradle().plugins().add("java");
        foo.buildGradle().append("""
            dependencies {
                implementation 'org.slf4j:slf4j-api:1.7.20'
            }
            """);

        gradle.withArgs("dependencies", "--configuration", "unifiedClasspath").buildsSuccessfully();
        gradle.withArgs().buildsSuccessfully();
    }

    @Test
    void fails_if_dependency_was_removed_but_still_in_the_lock_file(
            GradleInvoker gradle, MavenRepo repo, SubProject foo) {
        String expectedError = "Locked dependencies missing from the resolution result";

        repo.publish(MavenArtifact.of("org:a:1.0"), MavenArtifact.of("org:b:1.0"));

        foo.buildGradle().append("""
            dependencies {
                implementation 'org:a:1.0'
                implementation 'org:b:1.0'
            }
            """).plugins().add("java");

        gradle.withArgs("--write-locks").buildsSuccessfully();

        foo.buildGradle().overwrite("""
            dependencies {
                implementation 'org:a:1.0'
            }
            """).plugins().add("java");

        // then: 'Check fails because locks are not up to date'
        InvocationResult failure = gradle.withArgs(":check").buildsWithFailure();
        assertThat(failure).task(":verifyLocks").failed();
        assertThat(failure).output().contains(expectedError);

        // and: 'Can finally write locks once again'
        gradle.withArgs("--write-locks").buildsSuccessfully();
        gradle.withArgs("verifyLocks").buildsSuccessfully();
    }

    @Test
    void why_works(GradleInvoker gradle, RootProject rootProject) {
        rootProject.buildGradle().plugins().add("java");
        rootProject.buildGradle().append("""
            dependencies {
                implementation 'ch.qos.logback:logback-classic:1.2.3' // brings in slf4j-api 1.7.25
            }
            """);

        gradle.withArgs("--write-locks").buildsSuccessfully();

        InvocationResult result =
                gradle.withArgs("why", "--dependency", "slf4j-api").buildsSuccessfully();
        assertThat(result).output().contains("org.slf4j:slf4j-api:1.7.25");
        assertThat(result).output().contains("ch.qos.logback:logback-classic -> 1.7.25");
    }

    @Test
    void why_with_hash_works(GradleInvoker gradle, RootProject rootProject) {
        rootProject.buildGradle().plugins().add("java");
        rootProject.buildGradle().append("""
            dependencies {
                implementation 'ch.qos.logback:logback-classic:1.2.3' // brings in slf4j-api 1.7.25
            }
            """);

        gradle.withArgs("--write-locks").buildsSuccessfully();

        InvocationResult result = gradle.withArgs("why", "--hash", "400d4d2a").buildsSuccessfully(); // slf4j-api
        assertThat(result).output().contains("org.slf4j:slf4j-api:1.7.25");
        assertThat(result).output().contains("ch.qos.logback:logback-classic -> 1.7.25");
    }

    @Test
    void why_with_comma_delimited_multiple_hashes_works(GradleInvoker gradle, RootProject rootProject) {
        rootProject.buildGradle().plugins().add("java");
        rootProject.buildGradle().append("""
            dependencies {
                implementation 'ch.qos.logback:logback-classic:1.2.3' // brings in slf4j-api 1.7.25
                implementation 'org:another-direct-dependency:1.2.3' // brings in org:another-transitive-dependency:3.2.1
            }
            """);

        gradle.withArgs("--write-locks").buildsSuccessfully();

        InvocationResult result =
                gradle.withArgs("why", "--hash", "400d4d2a,050d6518").buildsSuccessfully(); // both transitive
        // dependencies
        assertThat(result).output().contains("org.slf4j:slf4j-api:1.7.25");
        assertThat(result).output().contains("ch.qos.logback:logback-classic -> 1.7.25");
        assertThat(result).output().contains("org:another-transitive-dependency:3.2.1");
        assertThat(result).output().contains("org:another-direct-dependency -> 3.2.1");
    }

    // does_not_fail_if_subproject_evaluated_later_applies_base_plugin_in_own_build_file
    @Test
    void does_not_fail_if_subproject_evaluated_later_applies_base_plugin_in_own_build_file(
            GradleInvoker gradle, SubProject foo) {
        foo.buildGradle().plugins().add("java-library");
        foo.buildGradle().append("""
            dependencies {
                implementation project(':foo:bar')
            }
            """);

        // Need to make sure bar is evaluated after foo, so we're nesting it!
        SubProject bar = foo.subproject("bar");
        bar.buildGradle().plugins().add("java-library");

        gradle.withArgs("--write-locks").buildsSuccessfully();
    }

    @Test
    void locks_platform(GradleInvoker gradle, RootProject rootProject) {
        rootProject.buildGradle().plugins().add("java");
        rootProject.buildGradle().append("""
            dependencies {
                implementation platform('org:platform:1.0')
            }
            """);

        gradle.withArgs("--write-locks").buildsSuccessfully();

        assertThat(rootProject.file("versions.lock").text()).isEqualTo("""
            # Run ./gradlew writeVersionsLocks to regenerate this file. Blank lines are to minimize merge conflicts.

            org:platform:1.0 (1 constraints: a5041a2c)
            """);
    }

    @Test
    void verifylocks_is_cacheable(GradleInvoker gradle, RootProject rootProject) {
        rootProject.buildGradle().plugins().add("java");
        rootProject.buildGradle().append("""
            dependencies {
                implementation "org.slf4j:slf4j-api:$depVersion"
            }
            """);

        rootProject.gradlePropertiesFile().appendProperty("depVersion", "1.7.20");

        gradle.withArgs("--write-locks").buildsSuccessfully();

        // then: 'verifyLocks is up to date the second time'
        assertThat(gradle.withArgs("verifyLocks").buildsSuccessfully())
                .task(":verifyLocks")
                .succeeded();
        assertThat(gradle.withArgs("verifyLocks").buildsSuccessfully())
                .task(":verifyLocks")
                .upToDate();
    }

    @Test
    void verifylocks_current_lock_state_does_not_get_poisoned_by_existing_lock_file(
            GradleInvoker gradle, RootProject rootProject) {
        rootProject.buildGradle().plugins().add("java");
        rootProject.buildGradle().append("""
            dependencies {
                implementation "org.slf4j:slf4j-api:$depVersion"
            }
            """);

        rootProject.gradlePropertiesFile().appendProperty("depVersion", "1.7.20");

        gradle.withArgs("--write-locks").buildsSuccessfully();

        // then: 'verifyLocks fails if we lower the dep version'
        InvocationResult fail =
                gradle.withArgs("verifyLocks", "-PdepVersion=1.7.11").buildsWithFailure();

        // and: 'it expects the correct version to be 1.7.11'
        assertThat(fail).output().contains("""
            > Found dependencies whose dependents changed:
              -org.slf4j:slf4j-api:1.7.20 (1 constraints: 3c05433b)
              +org.slf4j:slf4j-api:1.7.11 (1 constraints: 3c05423b)
            """);
    }

    @Test
    void excludes_from_compileonly_do_not_obscure_real_dependency(GradleInvoker gradle, RootProject rootProject) {
        rootProject.buildGradle().plugins().add("java");
        rootProject.buildGradle().append("""
            dependencies {
                implementation 'ch.qos.logback:logback-classic:1.2.3'
            }
            configurations.compileOnly {
                // convoluted, but the idea is to exclude a transitive
                exclude group: 'org.slf4j', module: 'slf4j-api'
            }
            """);

        gradle.withArgs("--write-locks").buildsSuccessfully();

        // then: 'slf4j-api still appears in the lock file'
        assertThat(rootProject.file("versions.lock").text()).isEqualTo("""
            # Run ./gradlew writeVersionsLocks to regenerate this file. Blank lines are to minimize merge conflicts.

            ch.qos.logback:logback-classic:1.2.3 (1 constraints: 0805f935)

            org.slf4j:slf4j-api:1.7.25 (1 constraints: 400d4d2a)
            """);
    }

    @Test
    void can_resolve_configuration_dependency(
            GradleInvoker gradle, RootProject rootProject, SubProject foo, SubProject bar) {
        foo.buildGradle().plugins().add("java");
        foo.buildGradle().append("""
            dependencies {
                implementation project(path: ":bar", configuration: "fun")
            }
            """);

        bar.buildGradle().append("""
            configurations {
                fun
            }

            dependencies {
                fun 'ch.qos.logback:logback-classic:1.2.3'
            }
            """);

        // Make sure that we can still add dependencies to the original 'fun' configuration after resolving lock
        // state.
        //
        // Adding a constraint to 'fun' calls Configuration.preventIllegalMutation() which fails if observedState is
        // GRAPH_RESOLVED or ARTIFACTS_RESOLVED. That would happen if a configuration that extends from it has been
        // resolved.
        rootProject.buildGradle().append("""
            configurations.unifiedClasspath.incoming.afterResolve {
                project(':bar').dependencies.constraints {
                    fun 'some:other-dep'
                }
            }
            """);

        gradle.withArgs("--write-locks", "classes").buildsSuccessfully();
    }

    @Test
    void inter_project_normal_dependency_works(GradleInvoker gradle, SubProject foo, SubProject bar) {
        foo.buildGradle().plugins().add("java");
        foo.buildGradle().append("""
            dependencies {
                implementation project(":bar")
            }
            """);

        bar.buildGradle().plugins().add("java");

        gradle.withArgs("--write-locks", "classes").buildsSuccessfully();
    }

    @Test
    @SuppressWarnings("RegexpMultiline")
    void test_dependencies_appear_in_a_separate_block(GradleInvoker gradle, RootProject rootProject) {
        rootProject.buildGradle().plugins().add("java");
        rootProject.buildGradle().append("""
            dependencies {
                implementation 'ch.qos.logback:logback-classic:1.2.3'
                testImplementation 'org:test-dep-that-logs:1.0'
            }
            """);

        gradle.withArgs("--write-locks").buildsSuccessfully();

        String expected = """
            # Run ./gradlew writeVersionsLocks to regenerate this file. Blank lines are to minimize merge conflicts.

            ch.qos.logback:logback-classic:1.2.3 (1 constraints: 0805f935)

            org.slf4j:slf4j-api:1.7.25 (2 constraints: 7917e690)



            [Test dependencies]

            org:test-dep-that-logs:1.0 (1 constraints: a5041a2c)
            """;

        assertThat(rootProject.file("versions.lock").text()).isEqualTo(expected);
    }

    @Test
    @SuppressWarnings("RegexpMultiline")
    void locks_dependencies_from_extra_source_sets_that_end_in_test(GradleInvoker gradle, RootProject rootProject) {
        rootProject.buildGradle().plugins().add("java");
        rootProject.buildGradle().append("""
            sourceSets {
                eteTest
            }
            dependencies {
                implementation 'ch.qos.logback:logback-classic:1.2.3'
                testImplementation 'junit:junit:4.10'
                eteTestImplementation 'org:test-dep-that-logs:1.0'
            }
            """);

        gradle.withArgs("--write-locks").buildsSuccessfully();

        String expected = """
            # Run ./gradlew writeVersionsLocks to regenerate this file. Blank lines are to minimize merge conflicts.

            ch.qos.logback:logback-classic:1.2.3 (1 constraints: 0805f935)

            org.slf4j:slf4j-api:1.7.25 (2 constraints: 7917e690)



            [Test dependencies]

            junit:junit:4.10 (1 constraints: d904fd30)

            org:test-dep-that-logs:1.0 (1 constraints: a5041a2c)
            """;

        assertThat(rootProject.file("versions.lock").text()).isEqualTo(expected);
    }

    @Test
    @SuppressWarnings("RegexpMultiline")
    void versionslock_testproject_works(GradleInvoker gradle, RootProject rootProject) {
        rootProject.buildGradle().plugins().add("java");
        rootProject.buildGradle().append("""
            dependencies {
                implementation 'junit:junit:4.10'
            }

            versionsLock.testProject()
            """);

        gradle.withArgs("--write-locks").buildsSuccessfully();

        String expected = """
            # Run ./gradlew writeVersionsLocks to regenerate this file. Blank lines are to minimize merge conflicts.



            [Test dependencies]

            junit:junit:4.10 (1 constraints: d904fd30)
            """;

        assertThat(rootProject.file("versions.lock").text()).isEqualTo(expected);
    }

    @Test
    @SuppressWarnings("RegexpMultiline")
    void constraints_on_production_do_not_affect_scope_of_test_only_dependencies(
            GradleInvoker gradle, RootProject rootProject) {
        rootProject.buildGradle().plugins().add("java");
        rootProject.buildGradle().append("""
            dependencies {
                constraints {
                    implementation 'ch.qos.logback:logback-classic:1.2.3'
                }
                dependencies {
                    testImplementation 'ch.qos.logback:logback-classic'
                }
            }
            """);

        gradle.withArgs("--write-locks").buildsSuccessfully();

        String expected = """
            # Run ./gradlew writeVersionsLocks to regenerate this file. Blank lines are to minimize merge conflicts.



            [Test dependencies]

            ch.qos.logback:logback-classic:1.2.3 (1 constraints: 0805f935)

            org.slf4j:slf4j-api:1.7.25 (1 constraints: 400d4d2a)
            """;

        assertThat(rootProject.file("versions.lock").text()).isEqualTo(expected);
    }

    @Test
    void published_constraints_are_derived_from_lock_file_with_local_constraints(
            GradleInvoker gradle, RootProject rootProject, SubProject foo, SubProject bar) throws IOException {
        // Test with local constraints enabled
        rootProject
                .gradlePropertiesFile()
                .appendProperty("com.palantir.gradle.versions.publishLocalConstraints", "true");

        foo.buildGradle().plugins().add("java");
        foo.buildGradle().plugins().add("maven-publish");
        foo.buildGradle().append("""
            group = 'com.palantir.published-constraints'
            version = '1.2.3'
            publishing.publications {
                maven(MavenPublication) {
                    from components.java
                }
            }
            dependencies {
                implementation 'ch.qos.logback:logback-classic:1.2.3'
            }
            """);

        bar.buildGradle().plugins().add("java");
        bar.buildGradle().plugins().add("maven-publish");
        bar.buildGradle().append("""
            group = 'com.palantir.published-constraints'
            version = '1.2.3'
            publishing.publications {
                maven(MavenPublication) {
                    from components.java
                }
            }
            dependencies {
                implementation 'junit:junit:4.10'
            }
            """);

        gradle.withArgs("--write-locks").buildsSuccessfully();

        gradle.withArgs("generatePomFileForMavenPublication", "generateMetadataFileForMavenPublication")
                .buildsSuccessfully();

        MetadataFile.Dependency junitDep = new MetadataFile.Dependency("junit", "junit", Map.of("requires", "4.10"));
        MetadataFile.Dependency logbackDep =
                new MetadataFile.Dependency("ch.qos.logback", "logback-classic", Map.of("requires", "1.2.3"));
        MetadataFile.Dependency slf4jDep =
                new MetadataFile.Dependency("org.slf4j", "slf4j-api", Map.of("requires", "1.7.25"));
        MetadataFile.Dependency fooDep =
                new MetadataFile.Dependency("com.palantir.published-constraints", "foo", Map.of("requires", "1.2.3"));
        MetadataFile.Dependency barDep =
                new MetadataFile.Dependency("com.palantir.published-constraints", "bar", Map.of("requires", "1.2.3"));

        // then: "foo's metadata file has the right dependency constraints"
        Path fooMetadataFilename = foo.buildDir().path().resolve("publications/maven/module.json");
        MetadataFile fooMetadata = new ObjectMapper().readValue(fooMetadataFilename.toFile(), MetadataFile.class);

        assertThat(fooMetadata.variants())
                .containsExactlyInAnyOrder(
                        new MetadataFile.Variant(
                                "runtimeElements", Set.of(logbackDep), Set.of(barDep, junitDep, logbackDep, slf4jDep)),
                        new MetadataFile.Variant("apiElements", null, Set.of(barDep, junitDep, logbackDep, slf4jDep)));

        // and: "bar's metadata file has the right dependency constraints"
        Path barMetadataFilename = bar.buildDir().path().resolve("publications/maven/module.json");
        MetadataFile barMetadata = new ObjectMapper().readValue(barMetadataFilename.toFile(), MetadataFile.class);

        assertThat(barMetadata.variants())
                .containsExactlyInAnyOrder(
                        new MetadataFile.Variant(
                                "runtimeElements", Set.of(junitDep), Set.of(fooDep, junitDep, logbackDep, slf4jDep)),
                        new MetadataFile.Variant("apiElements", null, Set.of(fooDep, junitDep, logbackDep, slf4jDep)));
    }

    @Test
    void published_constraints_are_derived_from_lock_file_without_local_constraints(
            GradleInvoker gradle, SubProject foo, SubProject bar) throws IOException {
        foo.buildGradle().plugins().add("java");
        foo.buildGradle().plugins().add("maven-publish");
        foo.buildGradle().append("""
            publishing.publications {
                maven(MavenPublication) {
                    from components.java
                }
            }
            dependencies {
                implementation 'ch.qos.logback:logback-classic:1.2.3'
            }
            """);

        bar.buildGradle().plugins().add("java");
        bar.buildGradle().plugins().add("maven-publish");
        bar.buildGradle().append("""
            publishing.publications {
                maven(MavenPublication) {
                    from components.java
                }
            }
            dependencies {
                implementation 'junit:junit:4.10'
            }
            """);

        gradle.withArgs("--write-locks").buildsSuccessfully();

        gradle.withArgs("generatePomFileForMavenPublication", "generateMetadataFileForMavenPublication")
                .buildsSuccessfully();

        MetadataFile.Dependency junitDep = new MetadataFile.Dependency("junit", "junit", Map.of("requires", "4.10"));
        MetadataFile.Dependency logbackDep =
                new MetadataFile.Dependency("ch.qos.logback", "logback-classic", Map.of("requires", "1.2.3"));
        MetadataFile.Dependency slf4jDep =
                new MetadataFile.Dependency("org.slf4j", "slf4j-api", Map.of("requires", "1.7.25"));

        // then: "foo's metadata file has the right dependency constraints"
        Path fooMetadataFilename = foo.buildDir().path().resolve("publications/maven/module.json");
        MetadataFile fooMetadata = new ObjectMapper().readValue(fooMetadataFilename.toFile(), MetadataFile.class);

        assertThat(fooMetadata.variants())
                .containsExactlyInAnyOrder(
                        new MetadataFile.Variant("apiElements", null, Set.of(junitDep, logbackDep, slf4jDep)),
                        new MetadataFile.Variant(
                                "runtimeElements", Set.of(logbackDep), Set.of(junitDep, logbackDep, slf4jDep)));

        // and: "bar's metadata file has the right dependency constraints"
        Path barMetadataFilename = bar.buildDir().path().resolve("publications/maven/module.json");
        MetadataFile barMetadata = new ObjectMapper().readValue(barMetadataFilename.toFile(), MetadataFile.class);

        assertThat(barMetadata.variants())
                .containsExactlyInAnyOrder(
                        new MetadataFile.Variant("apiElements", null, Set.of(junitDep, logbackDep, slf4jDep)),
                        new MetadataFile.Variant(
                                "runtimeElements", Set.of(junitDep), Set.of(junitDep, logbackDep, slf4jDep)));
    }

    @Test
    void can_depend_on_artifact(GradleInvoker gradle, RootProject rootProject) {
        rootProject.buildGradle().plugins().add("java");
        rootProject.buildGradle().append("""
            dependencies {
                implementation "junit:junit:4.10@zip"
            }
            """);

        gradle.withArgs("--write-locks").buildsSuccessfully();
    }

    @Test
    void direct_test_dependency_that_is_also_a_production_transitive_ends_up_in_production(
            GradleInvoker gradle, RootProject rootProject) {
        rootProject.buildGradle().plugins().add("java");
        rootProject.buildGradle().append("""
            dependencies {
                implementation 'ch.qos.logback:logback-classic:1.2.3'
                testImplementation 'org.slf4j:slf4j-api:1.7.25'
            }
            """);

        gradle.withArgs("--write-locks").buildsSuccessfully();

        assertThat(rootProject.file("versions.lock").text()).isEqualTo("""
            # Run ./gradlew writeVersionsLocks to regenerate this file. Blank lines are to minimize merge conflicts.

            ch.qos.logback:logback-classic:1.2.3 (1 constraints: 0805f935)

            org.slf4j:slf4j-api:1.7.25 (2 constraints: 8012a437)
            """);
    }

    @Test
    void does_not_write_lock_file_when_property_gcvskipwritelocks_is_set(
            GradleInvoker gradle, RootProject rootProject) {
        rootProject.buildGradle().plugins().add("java");
        rootProject.buildGradle().append("""
            dependencies {
                testImplementation 'org.slf4j:slf4j-api:1.7.25'
            }
            """);

        String lockFileContent =
                "# Run ./gradlew writeVersionsLocks to regenerate this file. Blank lines are to minimize merge"
                        + " conflicts.\n";

        rootProject.file("versions.lock").overwrite(lockFileContent);

        InvocationResult result =
                gradle.withArgs("--write-locks", "-PgcvSkipWriteLocks").buildsSuccessfully();

        assertThat(rootProject.file("versions.lock").text()).isEqualTo(lockFileContent);
        assertThat(result).output().contains("Skipped writing lock state");
        assertThat(result).output().doesNotContain("Finished writing lock state");
    }

    private void verifyLockfile(GradleProject project, String... lines) {
        String lockfile = project.file("gradle.lockfile").text();
        for (String line : lines) {
            assertThat(lockfile).contains(line + "=runtimeClasspath");
        }
    }
}
