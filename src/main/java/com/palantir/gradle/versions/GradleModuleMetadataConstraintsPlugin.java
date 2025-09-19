/*
 * (c) Copyright 2025 Palantir Technologies Inc.
 * Licensed under the Apache License, Version 2.0.
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
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.plugins.JavaPlugin;

/**
 * Gradle plugin that enforces version alignment for projects within the same group.
 * When multiple projects share the same group ID, this plugin ensures they all
 * publish with the same version to maintain consistency.
 */
public abstract class GradleModuleMetadataConstraintsPlugin implements Plugin<Project> {
    private static final Logger log = Logging.getLogger(GradleModuleMetadataConstraintsPlugin.class);

    private static final String PUBLISH_PLATFORM_CONSTRAINTS_PROPERTY =
            "com.palantir.gradle.versions.publishPlatformConstraints";
    private static final String VERSION_ALIGNMENT_CONFIG_NAME = "sameVersionConstraints";
    private static final String VERSION_ALIGNMENT_REASON = "All modules in group must use the same version";

    @Override
    public final void apply(Project rootProject) {
        if (!rootProject.equals(rootProject.getRootProject())) {
            throw new IllegalStateException(
                    "GradleModuleMetadataConstraintsPlugin must be applied to the root project");
        }

        rootProject.afterEvaluate(_ignored -> {
            if ("true".equals(rootProject.findProperty(PUBLISH_PLATFORM_CONSTRAINTS_PROPERTY))) {
                enforceVersionAlignmentAcrossGroups(rootProject);
            }
        });
    }

    /**
     * Groups all publishable Java projects by their group ID and applies version
     * constraints to groups with multiple projects.
     */
    private void enforceVersionAlignmentAcrossGroups(Project rootProject) {
        Map<String, Set<Project>> projectsByGroup = rootProject.getAllprojects().stream()
                .filter(VersionsLockPlugin::isJavaLibrary)
                .collect(Collectors.groupingBy(p -> String.valueOf(p.getGroup()), Collectors.toSet()));

        projectsByGroup.values().stream()
                .filter(group -> group.size() > 1)
                .forEach(this::applyVersionConstraintsToGroup);
    }

    /**
     * Applies version constraints to ensure all projects in a group use the same version.
     * Each project gets constraints that force it to align with its siblings' versions.
     */
    private void applyVersionConstraintsToGroup(Set<Project> projectGroup) {
        List<Project> sortedProjects = projectGroup.stream()
                .sorted(Comparator.comparing(Project::getPath))
                .toList();

        String groupId = sortedProjects.get(0).getGroup().toString();

        log.lifecycle(
                "Aligning version for group '{}', modules: {}",
                groupId,
                sortedProjects.stream().map(Project::getName).collect(Collectors.toList()));

        sortedProjects.forEach(project -> {
            Configuration constraintsConfig = createConstraintsConfiguration(project);
            addSiblingVersionConstraints(project, sortedProjects, constraintsConfig);
            applyConstraintsToJavaProject(project, constraintsConfig);
        });
    }

    /**
     * Creates a configuration for holding version alignment constraints.
     * This configuration is not consumable or resolvable - it only holds constraints.
     */
    private Configuration createConstraintsConfiguration(Project project) {
        Configuration config = project.getConfigurations().maybeCreate(VERSION_ALIGNMENT_CONFIG_NAME);
        config.setDescription("Enforces version alignment for modules within the same group");
        config.setCanBeResolved(false);
        config.setCanBeConsumed(false);
        config.setVisible(false);
        return config;
    }

    /**
     * Adds strict version constraints for all sibling projects in the same group.
     */
    private void addSiblingVersionConstraints(
            Project project, List<Project> allGroupProjects, Configuration constraintsConfig) {
        allGroupProjects.stream().filter(sibling -> !sibling.equals(project)).forEach(sibling -> {
            String gav = sibling.getGroup() + ":" + sibling.getName();
            constraintsConfig
                    .getDependencyConstraints()
                    .add(project.getDependencies().getConstraints().create(gav, constraint -> {
                        constraint.version(v -> v.strictly(sibling.getVersion().toString()));
                        constraint.because(VERSION_ALIGNMENT_REASON);
                    }));
        });
    }

    /**
     * Applies the constraints configuration to Java project configurations
     * (API and runtime elements) so they're included in published metadata.
     */
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
