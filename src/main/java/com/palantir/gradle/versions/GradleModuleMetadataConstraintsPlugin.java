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

import com.google.common.collect.ImmutableList;
import com.palantir.gradle.versions.lockstate.LockState;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.gradle.api.Named;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.DependencyConstraint;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.publish.ivy.IvyPublication;
import org.gradle.api.publish.maven.MavenPublication;

public class GradleModuleMetadataConstraintsPlugin implements Plugin<Project> {
    private static final Logger log = Logging.getLogger(GradleModuleMetadataConstraintsPlugin.class);
    private static final String PUBLISH_LOCAL_CONSTRAINTS_PROPERTY =
            "com.palantir.gradle.versions.publishLocalConstraints";
    // needs a better name
    private static final String PUBLISH_PLATFORM_CONSTRAINTS_PROPERTY =
            "com.palantir.gradle.versions.publishPlatformConstraints";
    private static final String CONSTRAINTS_CONFIG = "sameVersionConstraints";
    private static final String GCV_CONSTRAINTS_CONFIG = "gcvPublishConstraints";

    @Override
    public void apply(Project root) {
        validateRootProject(root);

        root.getGradle().projectsEvaluated(_gradle -> {
            if (shouldPublishPlatformConstraints(root)) {
                configureSameVersionGroups(root);
            }

            configurePublishableConstraints(root);
        });
    }

    private void validateRootProject(Project root) {
        if (!root.equals(root.getRootProject())) {
            throw new IllegalStateException("same-version-constraints must be applied to the root project");
        }
    }

    private void configureSameVersionGroups(Project root) {
        Map<String, Set<Project>> projectsByGroup = root.getAllprojects().stream()
                .filter(this::isJavaLibrary)
                .collect(Collectors.groupingBy(p -> String.valueOf(p.getGroup()), Collectors.toSet()));

        projectsByGroup.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .forEach(this::configureGroup);
    }

    private void configureGroup(Map.Entry<String, Set<Project>> entry) {
        String group = entry.getKey();
        List<Project> projects = entry.getValue().stream()
                .sorted(Comparator.comparing(Project::getPath))
                .toList();

        log.lifecycle(
                "same-version-constraints: configuring group '{}' for {} modules: {}",
                group,
                projects.size(),
                projects.stream().map(Project::getName).toList());

        projects.forEach(project -> configureSameVersionConstraints(project, projects));
    }

    private void configureSameVersionConstraints(Project project, List<Project> siblings) {
        Configuration constraints = createConstraintsConfiguration(
                project, CONSTRAINTS_CONFIG, "Same-version constraints for modules in the same group");

        siblings.stream()
                .filter(sibling -> !sibling.equals(project))
                .map(sibling -> createConstraint(project, sibling))
                .forEach(constraints.getDependencyConstraints()::add);

        applyConstraintsToJavaConfigurations(project, constraints);
    }

    private void configurePublishableConstraints(Project rootProject) {
        List<DependencyConstraint> lockDeps = constructPublishableConstraintsFromLockFile(rootProject);

        rootProject.getAllprojects().forEach(project -> {
            List<DependencyConstraint> constraints = createLocalProjectConstraints(project);

            ImmutableList<DependencyConstraint> publishableConstraintsForSubproject =
                    ImmutableList.<DependencyConstraint>builder()
                            .addAll(constraints)
                            .addAll(lockDeps)
                            .build();

            if (publishableConstraintsForSubproject.isEmpty()) {
                return;
            }

            Configuration publishConstraints = createConstraintsConfiguration(
                    project, GCV_CONSTRAINTS_CONFIG, "Publishable constraints from the GCV versions.lock file");
            publishConstraints.getDependencyConstraints().addAll(publishableConstraintsForSubproject);

            applyConstraintsToJavaConfigurations(project, publishConstraints);
        });
    }

    private List<DependencyConstraint> createLocalProjectConstraints(Project currentProject) {
        if (!shouldPublishLocalConstraints(currentProject.getRootProject())) {
            return ImmutableList.of();
        }

        String reason = "Library published from the same project: "
                + currentProject.getRootProject().getName();

        return currentProject.getRootProject().getAllprojects().stream()
                .filter(project -> !currentProject.equals(project))
                .filter(this::isJavaLibrary)
                .map(library -> currentProject
                        .getDependencies()
                        .getConstraints()
                        .create(library, constraint -> constraint.because(reason)))
                .toList();
    }

    private static List<DependencyConstraint> constructPublishableConstraintsFromLockFile(Project rootProject) {
        LockState lockState =
                new ConflictSafeLockFile(rootProject.file("versions.lock").toPath()).readLocks();
        // We only publish the production locks.
        return lockState.productionLinesByModuleIdentifier().entrySet().stream()
                .map(e -> e.getKey() + ":" + e.getValue().version())
                .map(notation -> rootProject.getDependencies().getConstraints().create(notation, constraint -> {
                    constraint.version(v -> {
                        String version = Objects.requireNonNull(constraint.getVersion());
                        v.require(version);
                    });
                    constraint.because("Computed from com.palantir.consistent-versions' versions.lock in "
                            + rootProject.getName());
                }))
                .collect(Collectors.toList());
    }

    private DependencyConstraint createConstraint(Project project, Project sibling) {
        String gavNoVersion = sibling.getGroup() + ":" + sibling.getName();
        String version = String.valueOf(sibling.getVersion());

        return project.getDependencies().getConstraints().create(gavNoVersion, constraint -> {
            constraint.version(v -> v.require("[" + version + ",)"));
            constraint.because("Align modules from the same repository to the same version");
        });
    }

    private Configuration createConstraintsConfiguration(Project project, String name, String description) {
        Configuration config = project.getConfigurations().maybeCreate(name);
        config.setDescription(description);
        config.setCanBeResolved(false);
        config.setCanBeConsumed(false);
        config.setVisible(false);
        return config;
    }

    private void applyConstraintsToJavaConfigurations(Project project, Configuration constraints) {
        project.getPluginManager().withPlugin("java", _plugin -> {
            extendConfiguration(project, JavaPlugin.API_ELEMENTS_CONFIGURATION_NAME, constraints);
            extendConfiguration(project, JavaPlugin.RUNTIME_ELEMENTS_CONFIGURATION_NAME, constraints);
        });
    }

    private void extendConfiguration(Project project, String configName, Configuration constraints) {
        project.getConfigurations().named(configName).configure(conf -> conf.extendsFrom(constraints));
    }

    private boolean shouldPublishLocalConstraints(Project project) {
        return project.hasProperty(PUBLISH_LOCAL_CONSTRAINTS_PROPERTY)
                && "true".equals(project.findProperty(PUBLISH_LOCAL_CONSTRAINTS_PROPERTY));
    }

    private boolean shouldPublishPlatformConstraints(Project project) {
        return project.hasProperty(PUBLISH_PLATFORM_CONSTRAINTS_PROPERTY)
                && "true".equals(project.findProperty(PUBLISH_PLATFORM_CONSTRAINTS_PROPERTY));
    }

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

        List<String> jarPublications = publishing.getPublications().stream()
                .filter(this::hasJarArtifact)
                .map(Named::getName)
                .collect(ImmutableList.toImmutableList());

        if (jarPublications.isEmpty()) {
            log.debug("Project '{}' is not a library (no jar publications)", project.getDisplayName());
            return false;
        }

        log.debug("Project '{}' is a library (publishes jars: {})", project.getDisplayName(), jarPublications);
        return true;
    }

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
