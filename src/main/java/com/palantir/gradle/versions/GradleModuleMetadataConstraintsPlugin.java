/*
 * (c) Copyright 2025 Palantir Technologies Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package com.palantir.gradle.versions;

import com.google.common.collect.ImmutableList;
import com.palantir.gradle.versions.lockstate.LockState;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.DependencyConstraint;
import org.gradle.api.artifacts.ModuleIdentifier;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.result.ResolvedComponentResult;
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

    private static final String PUBLISH_LOCAL_CONSTRAINTS = "com.palantir.gradle.versions.publishLocalConstraints";
    private static final String PUBLISH_PLATFORM_CONSTRAINTS =
            "com.palantir.gradle.versions.publishPlatformConstraints";
    private static final String CONSTRAINTS_CONFIG = "sameVersionConstraints";
    private static final String API_CONSTRAINTS_CONFIG = "gcvApiPublishConstraints";
    private static final String RUNTIME_CONSTRAINTS_CONFIG = "gcvRuntimePublishConstraints";

    @Override
    public void apply(Project root) {
        if (!root.equals(root.getRootProject())) {
            throw new IllegalStateException("Plugin must be applied to the root project");
        }

        root.getPluginManager().apply(VersionsLockPlugin.class);

        root.allprojects(project -> {
            project.getPluginManager().withPlugin("java", _ignored -> {
                createConstraintsConfig(project, API_CONSTRAINTS_CONFIG, "API constraints for publishing");
                createConstraintsConfig(project, RUNTIME_CONSTRAINTS_CONFIG, "Runtime constraints for publishing");
                extendConfig(project, JavaPlugin.API_ELEMENTS_CONFIGURATION_NAME, API_CONSTRAINTS_CONFIG);
                extendConfig(project, JavaPlugin.RUNTIME_ELEMENTS_CONFIGURATION_NAME, RUNTIME_CONSTRAINTS_CONFIG);
            });
        });

        root.afterEvaluate(_ignored -> {
            if ("true".equals(root.findProperty(PUBLISH_PLATFORM_CONSTRAINTS))) {
                addSameVersionConstraints(root);
            }
            addPublishableConstraints(root);
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
     * Adds dependency constraints for publishing, from lock file and optionally local projects.
     */
    private void addPublishableConstraints(Project root) {
        LockState lockState =
                new ConflictSafeLockFile(root.file("versions.lock").toPath()).readLocks();
        List<DependencyConstraint> lockConstraints = lockState.productionLinesByModuleIdentifier().entrySet().stream()
                .map(e -> root.getDependencies()
                        .getConstraints()
                        .create(e.getKey() + ":" + e.getValue().version(), c -> {
                            c.version(v -> v.require(Objects.requireNonNull(c.getVersion())));
                            c.because("From versions.lock in " + root.getName());
                        }))
                .toList();

        root.getAllprojects().stream()
                .filter(p -> p.getPluginManager().hasPlugin("java"))
                .forEach(project -> {
                    List<DependencyConstraint> localConstraints = ImmutableList.of();
                    if ("true".equals(root.findProperty(PUBLISH_LOCAL_CONSTRAINTS))) {
                        localConstraints = getLocalConstraints(root, project);
                    }

                    if (localConstraints.isEmpty() && lockConstraints.isEmpty()) {
                        return;
                    }

                    Set<ModuleIdentifier> compileModules =
                            getModules(project, JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME);
                    Set<ModuleIdentifier> runtimeModules =
                            getModules(project, JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME);

                    Configuration apiConfig = project.getConfigurations().getByName(API_CONSTRAINTS_CONFIG);
                    addConstraintsToConfig(apiConfig, compileModules, lockConstraints, localConstraints);

                    Configuration runtimeConfig = project.getConfigurations().getByName(RUNTIME_CONSTRAINTS_CONFIG);
                    addConstraintsToConfig(runtimeConfig, runtimeModules, lockConstraints, localConstraints);
                });
    }

    private void addConstraintsToConfig(
            Configuration config,
            Set<ModuleIdentifier> allowedModules,
            List<DependencyConstraint> lockConstraints,
            List<DependencyConstraint> localConstraints) {
        lockConstraints.stream()
                .filter(c -> allowedModules.contains(c.getModule()))
                .forEach(c -> {
                    config.getDependencyConstraints().add(c);
                });
        localConstraints.forEach(c -> {
            config.getDependencyConstraints().add(c);
        });
    }

    private List<DependencyConstraint> getLocalConstraints(Project root, Project project) {
        String reason = "Library published from same project: " + root.getName();
        return root.getAllprojects().stream()
                .filter(lib -> !project.equals(lib))
                .filter(this::isJavaLibrary)
                .map(lib -> project.getDependencies().getConstraints().create(lib, c -> {
                    c.because(reason);
                }))
                .collect(Collectors.toList());
    }

    /**
     * Utility: Get all modules present in a configuration's resolved classpath.
     */
    private Set<ModuleIdentifier> getModules(Project project, String configName) {
        return project
                .getConfigurations()
                .getByName(configName)
                .getIncoming()
                .getResolutionResult()
                .getAllComponents()
                .stream()
                .map(ResolvedComponentResult::getModuleVersion)
                .filter(Objects::nonNull)
                .map(ModuleVersionIdentifier::getModule)
                .collect(Collectors.toSet());
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
