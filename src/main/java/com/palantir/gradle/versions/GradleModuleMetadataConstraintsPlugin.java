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

public abstract class GradleModuleMetadataConstraintsPlugin implements Plugin<Project> {

    private static final String PUBLISH_PLATFORM_CONSTRAINTS_PROPERTY =
            "com.palantir.gradle.versions.publishPlatformConstraints";
    private static final String VERSION_ALIGNMENT_CONFIG_NAME = "acrossGroupVersionConstraints";
    private static final String VERSION_ALIGNMENT_REASON = "All modules in group must use the same version";

    @Override
    public final void apply(Project rootProject) {
        if (!rootProject.equals(rootProject.getRootProject())) {
            throw new IllegalStateException(
                    "GradleModuleMetadataConstraintsPlugin must be applied to the root project");
        }

        rootProject.afterEvaluate(_ignored -> {
            if (publishPlatformConstraints(rootProject)) {
                enforceVersionAlignmentAcrossGroups(rootProject);
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

        projectsByGroup.values().stream()
                .filter(group -> group.size() > 1)
                .forEach(this::applyVersionConstraintsToGroup);
    }

    private void applyVersionConstraintsToGroup(Set<Project> projectGroup) {
        List<Project> sortedProjects = projectGroup.stream()
                .sorted(Comparator.comparing(Project::getPath))
                .toList();

        sortedProjects.forEach(project -> {
            Configuration constraintsConfig = createConstraintsConfiguration(project);
            addSiblingVersionConstraints(project, sortedProjects, constraintsConfig);
            applyConstraintsToJavaProject(project, constraintsConfig);
        });
    }

    private Configuration createConstraintsConfiguration(Project project) {
        Configuration config = project.getConfigurations().maybeCreate(VERSION_ALIGNMENT_CONFIG_NAME);
        config.setDescription("Enforces version alignment for modules within the same group");
        config.setCanBeResolved(false);
        config.setCanBeConsumed(false);
        config.setVisible(false);
        return config;
    }

    private void addSiblingVersionConstraints(
            Project project, List<Project> allGroupProjects, Configuration constraintsConfig) {
        allGroupProjects.stream().filter(sibling -> !sibling.equals(project)).forEach(sibling -> {
            String gav = sibling.getGroup() + ":" + sibling.getName();
            constraintsConfig
                    .getDependencyConstraints()
                    .add(project.getDependencies().getConstraints().create(gav, constraint -> {
                        constraint.version(v -> v.require(sibling.getVersion().toString()));
                        constraint.because(VERSION_ALIGNMENT_REASON);
                    }));
        });
    }

    private void applyConstraintsToJavaProject(Project project, Configuration constraintsConfig) {
        if (!project.getPluginManager().hasPlugin("java")) {
            return;
        }

        project.getConfigurations()
                .named(JavaPlugin.API_ELEMENTS_CONFIGURATION_NAME)
                .configure(conf -> conf.extendsFrom(constraintsConfig));

        project.getConfigurations()
                .named(JavaPlugin.RUNTIME_ELEMENTS_CONFIGURATION_NAME)
                .configure(conf -> conf.extendsFrom(constraintsConfig));
    }
}
