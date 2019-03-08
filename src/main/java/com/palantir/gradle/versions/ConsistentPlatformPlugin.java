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

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.gradle.api.GradleException;
import org.gradle.api.Incubating;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.DependencyConstraint;
import org.gradle.api.artifacts.DependencyConstraintSet;
import org.gradle.api.artifacts.ExternalDependency;
import org.gradle.api.artifacts.ModuleIdentifier;
import org.gradle.api.artifacts.dsl.DependencyConstraintHandler;
import org.gradle.api.plugins.JavaBasePlugin;
import org.gradle.api.plugins.JavaPlatformExtension;
import org.gradle.api.plugins.JavaPlatformPlugin;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.publish.maven.MavenPublication;
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin;
import org.gradle.util.GradleVersion;

/**
 * Sets up a {@code bom} publication that publishes constraints for _all_ dependencies found in the lock file that is
 * managed by {@link VersionsLockPlugin}.
 */
@Incubating
public class ConsistentPlatformPlugin implements Plugin<Project> {
    private static final GradleVersion MINIMUM_GRADLE_VERSION = GradleVersion.version("5.2");
    private static final String JAVA_PLATFORM_COMPONENT = "javaPlatform";
    private static final String PLUGIN_NAME = "com.palantir.consistent-platform";

    private static final ImmutableList<String> JAVA_PLATFORM_CONFIGURATIONS = ImmutableList.of(
            JavaPlatformPlugin.API_CONFIGURATION_NAME,
            JavaPlatformPlugin.API_ELEMENTS_CONFIGURATION_NAME,
            JavaPlatformPlugin.ENFORCED_API_ELEMENTS_CONFIGURATION_NAME,
            JavaPlatformPlugin.RUNTIME_CONFIGURATION_NAME,
            JavaPlatformPlugin.RUNTIME_ELEMENTS_CONFIGURATION_NAME,
            JavaPlatformPlugin.ENFORCED_RUNTIME_ELEMENTS_CONFIGURATION_NAME,
            JavaPlatformPlugin.CLASSPATH_CONFIGURATION_NAME);

    @Override
    public final void apply(Project project) {
        checkPreconditions(project);

        project.getPluginManager().apply(MavenPublishPlugin.class);
        project.getPluginManager().apply(JavaPlatformPlugin.class);

        // Need this so we can declare BOM dependencies
        project.getExtensions().getByType(JavaPlatformExtension.class).allowDependencies();

        // Configure root project versions-lock
        project.getRootProject().getPlugins().withType(VersionsLockPlugin.class, plugin -> {
            VersionsLockPlugin.applyLocksTo(project, JavaPlatformPlugin.API_CONFIGURATION_NAME);
        });

        // Add constraints on all other published local projects
        DependencyConstraintHandler myConstraints = project.getDependencies().getConstraints();
        project.getRootProject().allprojects(otherProject -> {
            if (otherProject == project) {
                return;
            }
            otherProject.getPluginManager().withPlugin("maven-publish", plugin -> {
                otherProject.afterEvaluate(p -> {
                    PublishingExtension publishing = otherProject.getExtensions().getByType(PublishingExtension.class);
                    if (!publishing.getPublications().isEmpty()) {
                        myConstraints.add(JavaPlatformPlugin.API_CONFIGURATION_NAME, otherProject);
                    }
                });
            });
        });

        // If versions-props is applied, make it so that it doesn't apply its recommendations to any of the
        // javaPlatform's configurations.
        project.getPlugins().withType(VersionsPropsPlugin.class, plugin -> {
            JAVA_PLATFORM_CONFIGURATIONS.forEach(name ->
                    project.getConfigurations().named(name).configure(VersionsPropsPlugin::disableRecommendations));

            // But, explicitly pick up constraints from 'rootConfiguration' that didn't come from the lock file
            // This is so that BOM imports are picked up, for instance
            project.getConfigurations().named(JavaPlatformPlugin.API_CONFIGURATION_NAME).configure(api -> {

                Map<ModuleIdentifier, DependencyConstraint> existingConstraints =
                        ensureConstraintsAreUnique(api.getAllDependencyConstraints());

                Configuration rootConfiguration =
                        project.getConfigurations().getByName(VersionsPropsPlugin.ROOT_CONFIGURATION_NAME);

                mergePlatformDependenciesWithExistingConstraints(rootConfiguration, api, existingConstraints);

                DependencyConstraintSet ownConstraints = api.getDependencyConstraints();
                ownConstraints.clear();
                // re-add the constraints that we didn't remove in our filtering
                // i.e. that don't apply to platforms
                ownConstraints.addAll(existingConstraints.values());

                // Need this to ensure that other constraints aren't being inherited anymore...
                // Otherwise, we can't remove constraints from the lock file that clash with platform dependencies
                api.setExtendsFrom(ImmutableList.of());
            });
        });

        project.getGradle().projectsEvaluated(gradle -> {
            if (!gradle.getRootProject().getPlugins().hasPlugin(VersionsLockPlugin.class)) {
                throw new GradleException("Need to apply 'com.palantir.versions-lock' on the root project when using "
                                + "'" + PLUGIN_NAME + "'");
            }
        });

        PublishingExtension ourPublishingExtension = project.getExtensions().getByType(PublishingExtension.class);
        ourPublishingExtension.getPublications().register("bom", MavenPublication.class, publication -> {
            publication.from(project.getComponents().getByName(JAVA_PLATFORM_COMPONENT));
        });
    }

    private static Map<ModuleIdentifier, DependencyConstraint> ensureConstraintsAreUnique(
            DependencyConstraintSet existingConstraintSet) {

        Map<ModuleIdentifier, DependencyConstraint> existingConstraints = new HashMap<>();
        List<String> failures = new ArrayList<>();
        for (DependencyConstraint constraint : existingConstraintSet) {
            ModuleIdentifier key = constraint.getModule();
            if (!existingConstraints.containsKey(key)) {
                existingConstraints.put(key, constraint);
            } else {
                failures.add(String.format("%s:%n"
                                + "\t1) %s%n"
                                + "\t2) %s", key, existingConstraints.get(key), constraint));
            }
        }
        if (!failures.isEmpty()) {
            throw new GradleException("Not allowed to have multiple constraints on the same module as "
                    + "auto-merging is impossible:\n" + Joiner.on('\n').join(failures));
        }
        return existingConstraints;
    }

    private static void mergePlatformDependenciesWithExistingConstraints(
            Configuration platformDependenciesConfiguration,
            Configuration api,
            Map<ModuleIdentifier, DependencyConstraint> existingConstraints) {
        platformDependenciesConfiguration
                .getAllDependencies()
                .matching(userDep -> userDep instanceof ExternalDependency
                        && GradleUtils.isPlatform(((ExternalDependency) userDep).getAttributes()))
                .stream()
                .map(dep -> (ExternalDependency) dep)
                .forEach(platformDep -> {
                    // Remove platformDep's module from the 'existingConstraints', and add an explicit
                    // dependency instead. This is to work around a gradle bug where the BOM would contain two
                    // entries instead of one, which is invalid:
                    // https://github.com/gradle/gradle/issues/8238
                    DependencyConstraint constraint = existingConstraints.remove(platformDep.getModule());
                    ExternalDependency newDep = platformDep.copy();
                    newDep.version(vc -> {
                        if (constraint != null) {
                            Preconditions.checkNotNull(
                                    constraint.getVersion(),
                                    "Expected constraint for platform dependency to have a version: %s",
                                    constraint);
                            vc.require(constraint.getVersion());
                        }
                    });
                    api.getDependencies().add(newDep);
                });
    }

    private static void checkPreconditions(Project project) {
        if (GradleVersion.current().compareTo(MINIMUM_GRADLE_VERSION) < 0) {
            throw new GradleException("The consistent-platform plugin requires at least " + MINIMUM_GRADLE_VERSION);
        }

        // JavaPlatformPlugin is incompatible with JavaBasePlugin (they both define a configuration called 'api', etc)
        if (project.getPlugins().hasPlugin(JavaBasePlugin.class)) {
            failBecauseJavaPluginApplied(project);
        }

        project.getPlugins()
                .withType(JavaBasePlugin.class)
                .configureEach(p -> failBecauseJavaPluginApplied(project));
    }

    private static void failBecauseJavaPluginApplied(Project project) {
        throw new GradleException(String.format(
                "Cannot apply '%s' to %s as it already has the 'java' plugin applied. Either stop applying the "
                        + "'java' plugin or consider making a new project solely for platform publishing.",
                PLUGIN_NAME,
                project));
    }
}
