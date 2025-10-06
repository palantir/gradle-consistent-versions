/*
 * (c) Copyright 2025 Palantir Technologies Inc. All rights reserved.
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

import java.util.Comparator;
import java.util.Set;
import java.util.stream.Collectors;
import org.gradle.api.NamedDomainObjectProvider;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.plugins.JavaPlugin;

public class AddVirtualPlatformPlugin implements Plugin<Project> {

    private static final String PUBLISH_PLATFORM_CONSTRAINTS_PROPERTY =
            "com.palantir.gradle.versions.addVirtualPlatformConstraint";

    @Override
    public final void apply(Project rootProject) {
        if (!rootProject.equals(rootProject.getRootProject())) {
            throw new IllegalStateException("AddVirtualPlatformPlugin must be applied to the root project");
        }

        rootProject.afterEvaluate(project -> {
            if (shouldPublishPlatformConstraints(project)) {
                enforceVersionAlignment(project);
            }
        });
    }

    private static boolean shouldPublishPlatformConstraints(Project project) {
        return project.hasProperty(PUBLISH_PLATFORM_CONSTRAINTS_PROPERTY)
                && "true".equals(project.property(PUBLISH_PLATFORM_CONSTRAINTS_PROPERTY));
    }

    private void enforceVersionAlignment(Project rootProject) {
        rootProject.getAllprojects().stream()
                .filter(VersionsLockPlugin::isJavaLibrary)
                .collect(Collectors.groupingBy(p -> String.valueOf(p.getGroup()), Collectors.toSet()))
                .values()
                .stream()
                .filter(group -> group.size() > 1)
                .forEach(this::applyVirtualPlatform);
    }

    private void applyVirtualPlatform(Set<Project> projectGroup) {
        Project firstProject = projectGroup.stream()
                .min(Comparator.comparing(Project::getPath))
                .orElseThrow();

        String platformCoordinates = firstProject.getGroup() + ":palantir-virtual-platform";

        projectGroup.stream()
                .filter(p -> p.getPluginManager().hasPlugin("java"))
                .forEach(project -> addVirtualPlatformToProject(project, platformCoordinates));
    }

    private void addVirtualPlatformToProject(Project project, String platformCoordinates) {
        NamedDomainObjectProvider<Configuration> constraints = project.getConfigurations()
                .register("virtualPlatformConstraints", config -> {
                    config.setDescription(
                            "Enforces version alignment via virtual platform for modules within the same group");
                    config.setCanBeResolved(false);
                    config.setCanBeConsumed(false);
                    config.setVisible(false);

                    config.getDependencyConstraints()
                            .add(project.getDependencies().getConstraints().create(platformCoordinates, constraint -> {
                                constraint.version(
                                        v -> v.require(project.getVersion().toString()));
                                constraint.because("Virtual platform for version alignment across group");
                            }));
                });

        project.getConfigurations()
                .named(JavaPlugin.API_ELEMENTS_CONFIGURATION_NAME)
                .configure(conf -> conf.extendsFrom(constraints.get()));

        project.getConfigurations()
                .named(JavaPlugin.RUNTIME_ELEMENTS_CONFIGURATION_NAME)
                .configure(conf -> conf.extendsFrom(constraints.get()));
    }
}
