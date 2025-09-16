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

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;

/**
 * A Gradle plugin that ensures all modules published from the same repository
 * (identified by group ID) use the same version when consumed by other projects.
 *
 * This plugin adds dependency constraints to each module's published GMM that
 * force all sibling modules to align to the same version.
 *
 * Apply to root project only:
 * ```gradle
 * apply plugin: 'com.palantir.same-version-constraints'
 * ```
 */
public class SameVersionConstraintsPlugin implements Plugin<Project> {
    private static final Logger log = Logging.getLogger(SameVersionConstraintsPlugin.class);

    @Override
    public void apply(Project project) {
        if (!project.equals(project.getRootProject())) {
            throw new IllegalStateException("same-version-constraints plugin must only be applied to root project");
        }

        // Configure all projects after evaluation
        project.getGradle().projectsEvaluated(_gradle -> {
            Map<String, Set<Project>> projectsByGroup = discoverProjectsByGroup(project);

            projectsByGroup.entrySet().stream()
                    .filter(entry -> entry.getValue().size() > 1)
                    .forEach(entry -> {
                        String group = entry.getKey();
                        Set<Project> projects = entry.getValue();
                        log.lifecycle(
                                "Configuring same-version constraints for group '{}' with {} modules: {}",
                                group,
                                projects.size(),
                                projects.stream().map(Project::getName).collect(Collectors.toList()));
                        configureSameVersionConstraints(projects);
                    });
        });
    }

    /**
     * Discovers all projects grouped by their group ID.
     */
    private Map<String, Set<Project>> discoverProjectsByGroup(Project rootProject) {
        return rootProject.getAllprojects().stream()
                .filter(this::isPublishedLibrary)
                .collect(Collectors.groupingBy(p -> String.valueOf(p.getGroup()), Collectors.toSet()));
    }

    /**
     * Configures each project to include constraints for all sibling modules.
     * This approach adds constraints that will appear in the published GMM.
     */
    private void configureSameVersionConstraints(Set<Project> projects) {
        projects.forEach(project -> {
            // Create a configuration to hold the constraints
            Configuration constraintsConfig = project.getConfigurations().maybeCreate("sameVersionConstraints");
            constraintsConfig.setVisible(false);
            constraintsConfig.setCanBeConsumed(false);
            constraintsConfig.setCanBeResolved(false);
            constraintsConfig.setDescription("Same-version constraints for modules in the same group");

            // Add constraints for all sibling modules
            projects.forEach(sibling -> {
                if (!sibling.equals(project)) {
                    String siblingGav = String.format("%s:%s", sibling.getGroup(), sibling.getName());

                    constraintsConfig
                            .getDependencyConstraints()
                            .add(project.getDependencies().getConstraints().create(siblingGav, constraint -> {
                                // Use require to set minimum version that allows upgrades
                                constraint.version(v -> v.require(String.valueOf(sibling.getVersion())));
                                constraint.because("All modules from the same repository must use the same version");
                            }));
                }
            });

            // Make api and implementation configurations extend from our constraints
            extendConfigurationWithConstraints(project, "api", constraintsConfig);
            extendConfigurationWithConstraints(project, "implementation", constraintsConfig);

            log.info(
                    "Added {} same-version constraints to {}:{}",
                    projects.size() - 1,
                    project.getGroup(),
                    project.getName());
        });
    }

    /**
     * Makes a configuration extend from the constraints configuration if it exists.
     */
    private void extendConfigurationWithConstraints(
            Project project, String configName, Configuration constraintsConfig) {
        Configuration config = project.getConfigurations().findByName(configName);
        if (config != null) {
            config.extendsFrom(constraintsConfig);
            log.debug(
                    "Configuration '{}' in project {} now extends from sameVersionConstraints",
                    configName,
                    project.getName());
        }
    }

    /**
     * Checks if a project publishes a library.
     * Supports both standard publishing and Palantir's external-publish-jar plugin.
     */
    private boolean isPublishedLibrary(Project project) {
        return project.getPlugins().hasPlugin("com.palantir.external-publish-jar")
                || project.getPlugins().hasPlugin("java-library")
                || project.getPlugins().hasPlugin("java");
    }
}
