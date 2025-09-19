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
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.publish.ivy.IvyPublication;
import org.gradle.api.publish.maven.MavenPublication;

/**
 * Gradle plugin to align and publish dependency constraints for Java projects.
 */
public class GradleModuleMetadataConstraintsPlugin implements Plugin<Project> {
    private static final Logger log = Logging.getLogger(GradleModuleMetadataConstraintsPlugin.class);

    private static final String PUBLISH_PLATFORM_CONSTRAINTS =
            "com.palantir.gradle.versions.publishPlatformConstraints";
    private static final String CONSTRAINTS_CONFIG = "sameVersionConstraints";

    @Override
    public void apply(Project root) {
        if (!root.equals(root.getRootProject())) {
            throw new IllegalStateException("Plugin must be applied to the root project");
        }

        root.getPluginManager().apply(VersionsLockPlugin.class);

        root.afterEvaluate(_ignored -> {
            if ("true".equals(root.findProperty(PUBLISH_PLATFORM_CONSTRAINTS))) {
                addSameVersionConstraints(root);
            }
        });
    }

    /**
     * For groups with multiple Java projects, add constraints to keep their versions aligned.
     */
    private void addSameVersionConstraints(Project root) {
        Map<String, Set<Project>> projectsByGroup = root.getAllprojects().stream()
                .filter(this::isJavaLibrary)
                .collect(Collectors.groupingBy(p -> String.valueOf(p.getGroup()), Collectors.toSet()));

        projectsByGroup.values().stream().filter(group -> group.size() > 1).forEach(this::alignGroupVersions);
    }

    private void alignGroupVersions(Set<Project> group) {
        List<Project> projects =
                group.stream().sorted(Comparator.comparing(Project::getPath)).toList();
        String groupId = projects.get(0).getGroup().toString();

        log.lifecycle(
                "Aligning group '{}' for modules: {}",
                groupId,
                projects.stream().map(Project::getName).collect(Collectors.toList()));

        projects.forEach(project -> {
            Configuration constraints =
                    createConstraintsConfig(project, CONSTRAINTS_CONFIG, "Align sibling modules to same version");

            projects.stream().filter(sibling -> !sibling.equals(project)).forEach(sibling -> {
                String gav = sibling.getGroup() + ":" + sibling.getName();
                constraints
                        .getDependencyConstraints()
                        .add(project.getDependencies().getConstraints().create(gav, c -> {
                            c.version(v -> v.require("[" + sibling.getVersion() + ",)"));
                            c.because("Align modules from same group");
                        }));
            });

            if (project.getPluginManager().hasPlugin("java")) {
                extendConfig(project, JavaPlugin.API_ELEMENTS_CONFIGURATION_NAME, constraints.getName());
                extendConfig(project, JavaPlugin.RUNTIME_ELEMENTS_CONFIGURATION_NAME, constraints.getName());
            }
        });
    }

    /**
     * Utility: Create a configuration for dependency constraints.
     */
    private Configuration createConstraintsConfig(Project project, String name, String description) {
        Configuration config = project.getConfigurations().maybeCreate(name);
        config.setDescription(description);
        config.setCanBeResolved(false);
        config.setCanBeConsumed(false);
        config.setVisible(false);
        return config;
    }

    /**
     * Utility: Extend a configuration with another.
     */
    private void extendConfig(Project project, String baseConfig, String constraintsConfig) {
        project.getConfigurations().named(baseConfig).configure(conf -> {
            conf.extendsFrom(project.getConfigurations().getByName(constraintsConfig));
        });
    }

    /**
     * Determines if the project is a Java library (publishes a JAR).
     */
    private boolean isJavaLibrary(Project project) {
        // Nebula creates publications lazily, so we check for it explicitly
        if (project.getPluginManager().hasPlugin("nebula.maven-publish")) {
            log.debug("Project '{}' is a library (nebula.maven-publish)", project.getDisplayName());
            return true;
        }

        PublishingExtension publishing = project.getExtensions().findByType(PublishingExtension.class);
        if (publishing == null) {
            log.debug("Project '{}' is not a library (no publishing)", project.getDisplayName());
            return false;
        }

        return publishing.getPublications().stream().anyMatch(this::hasJarArtifact);
    }

    /**
     * Determines if a publication (Maven or Ivy) includes a JAR artifact.
     */
    private boolean hasJarArtifact(Object publication) {
        if (publication instanceof MavenPublication maven) {
            return maven.getArtifacts().stream().anyMatch(a -> "jar".equals(a.getExtension()));
        }
        if (publication instanceof IvyPublication ivy) {
            return ivy.getArtifacts().stream().anyMatch(a -> "jar".equals(a.getExtension()));
        }
        log.warn(
                "Unknown publication type '{}', assuming it's a library",
                publication.getClass().getName());
        return true;
    }
}
