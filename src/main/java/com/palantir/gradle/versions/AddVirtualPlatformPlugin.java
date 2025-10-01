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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
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
            throw new IllegalStateException(
                    "GradleModuleMetadataConstraintsPlugin must be applied to the root project");
        }

        rootProject.afterEvaluate(project -> {
            if (publishPlatformConstraints(project)) {
                enforceVersionAlignmentAcrossGroups(project);
            }
        });
    }

    public static boolean publishPlatformConstraints(Project project) {
        return project.hasProperty(PUBLISH_PLATFORM_CONSTRAINTS_PROPERTY)
                && "true".equals(project.property(PUBLISH_PLATFORM_CONSTRAINTS_PROPERTY));
    }

    private void enforceVersionAlignmentAcrossGroups(Project rootProject) {
        Map<String, Set<Project>> projectsByGroup = rootProject.getAllprojects().stream()
                .filter(VersionsLockPlugin::isJavaLibrary)
                .collect(Collectors.groupingBy(p -> String.valueOf(p.getGroup()), Collectors.toSet()));

        projectsByGroup.values().stream().filter(group -> group.size() > 1).forEach(this::applyVirtualPlatformToGroup);
    }

    private void applyVirtualPlatformToGroup(Set<Project> projectGroup) {
        List<Project> sortedProjects = projectGroup.stream()
                .sorted(Comparator.comparing(Project::getPath))
                .toList();

        String platformCoordinates = sortedProjects.get(0).getGroup() + ":" + "palantir-virtual-platform:";

        sortedProjects.forEach(project -> {
            if (project.getPluginManager().hasPlugin("java")) {
                Configuration constraintsConfig = createConstraintsConfiguration(project);
                addVirtualPlatformConstraint(project, platformCoordinates, constraintsConfig);
                applyConstraintsToJavaProject(project, constraintsConfig);
            }
        });
    }

    private Configuration createConstraintsConfiguration(Project project) {
        Configuration config = project.getConfigurations().maybeCreate("virtualPlatformConstraints");
        config.setDescription("Enforces version alignment via virtual platform for modules within the same group");
        config.setCanBeResolved(false);
        config.setCanBeConsumed(false);
        config.setVisible(false);
        return config;
    }

    private void addVirtualPlatformConstraint(
            Project project, String platformCoordinates, Configuration constraintsConfig) {
        constraintsConfig
                .getDependencyConstraints()
                .add(project.getDependencies().getConstraints().create(platformCoordinates, constraint -> {
                    constraint.version(v -> v.require(project.getVersion().toString()));
                    constraint.because("Virtual platform for version alignment across group");
                }));
    }

    private void applyConstraintsToJavaProject(Project project, Configuration constraintsConfig) {
        project.getConfigurations()
                .named(JavaPlugin.API_ELEMENTS_CONFIGURATION_NAME)
                .configure(conf -> conf.extendsFrom(constraintsConfig));

        project.getConfigurations()
                .named(JavaPlugin.RUNTIME_ELEMENTS_CONFIGURATION_NAME)
                .configure(conf -> conf.extendsFrom(constraintsConfig));
    }
}
