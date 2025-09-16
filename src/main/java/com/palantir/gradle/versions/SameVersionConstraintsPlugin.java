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
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.gradle.api.Action;
import org.gradle.api.Named;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.DependencyConstraint;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.publish.Publication;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.publish.ivy.IvyPublication;
import org.gradle.api.publish.maven.MavenPublication;

public class SameVersionConstraintsPlugin implements Plugin<Project> {
    private static final Logger log = Logging.getLogger(SameVersionConstraintsPlugin.class);
    private static final String PUBLISH_LOCAL_CONSTRAINTS_PROPERTY =
            "com.palantir.gradle.versions.publishLocalConstraints";

    @Override
    public final void apply(Project root) {
        if (!root.equals(root.getRootProject())) {
            throw new IllegalStateException("same-version-constraints must be applied to the root project");
        }

        root.getGradle().projectsEvaluated(_gradle -> {
            Map<String, Set<Project>> byGroup = discoverProjectsByGroup(root);

            byGroup.entrySet().stream()
                    .filter(entry -> entry.getValue().size() > 1)
                    .forEach(entry -> {
                        String group = entry.getKey();
                        Set<Project> projects = entry.getValue().stream()
                                .sorted(Comparator.comparing(Project::getPath))
                                .collect(Collectors.toCollection(LinkedHashSet::new));

                        log.lifecycle(
                                "same-version-constraints: configuring group '{}' for {} modules: {}",
                                group,
                                projects.size(),
                                projects.stream().map(Project::getName).collect(Collectors.toList()));

                        configureGroup(projects);
                    });

            // Add GCV publishable constraints if enabled
            if (shouldPublishLocalConstraints(root)) {
                root.getAllprojects().forEach(this::configureGcvPublishableConstraints);
            }
        });
    }

    private void configureGcvPublishableConstraints(Project project) {
        List<DependencyConstraint> localProjectConstraints = constructPublishableConstraintsFromLocalProjects(
                project, project.getDependencies().getConstraints()::create);

        if (!localProjectConstraints.isEmpty()) {
            Configuration publishConstraints = project.getConfigurations().maybeCreate("gcvPublishConstraints");
            publishConstraints.setDescription("Publishable constraints from the GCV versions.lock file");
            publishConstraints.setCanBeResolved(false);
            publishConstraints.setCanBeConsumed(false);
            publishConstraints.getDependencyConstraints().addAll(localProjectConstraints);

            // Enrich the configurations being published as part of the java component
            project.getPluginManager().withPlugin("java", _plugin -> {
                extendConfiguration(project, JavaPlugin.API_ELEMENTS_CONFIGURATION_NAME, publishConstraints);
                extendConfiguration(project, JavaPlugin.RUNTIME_ELEMENTS_CONFIGURATION_NAME, publishConstraints);
            });
        }
    }

    private static List<DependencyConstraint> constructPublishableConstraintsFromLocalProjects(
            Project currentProject, DependencyConstraintCreator constraintCreator) {
        return currentProject.getRootProject().getAllprojects().stream()
                .filter(project -> !currentProject.equals(project))
                .filter(SameVersionConstraintsPlugin::isJavaLibrary)
                .map(libraryProject -> constraintCreator.create(
                        libraryProject,
                        constraint -> constraint.because("Library published from the same project: "
                                + currentProject.getRootProject().getName())))
                .collect(Collectors.toList());
    }

    private static boolean shouldPublishLocalConstraints(Project project) {
        return project.hasProperty(PUBLISH_LOCAL_CONSTRAINTS_PROPERTY)
                && "true".equals(project.property(PUBLISH_LOCAL_CONSTRAINTS_PROPERTY));
    }

    private Map<String, Set<Project>> discoverProjectsByGroup(Project root) {
        return root.getAllprojects().stream()
                .filter(SameVersionConstraintsPlugin::isJavaLibrary)
                .collect(Collectors.groupingBy(p -> String.valueOf(p.getGroup()), Collectors.toSet()));
    }

    private void configureGroup(Set<Project> projects) {
        projects.forEach(project -> {
            Configuration constraintsCfg = createSameVersionConstraintsConfiguration(project);
            addSiblingProjectConstraints(project, projects, constraintsCfg);
            applyConstraintsToJavaConfigurations(project, constraintsCfg);
        });
    }

    private Configuration createSameVersionConstraintsConfiguration(Project project) {
        Configuration constraintsCfg = project.getConfigurations().maybeCreate("sameVersionConstraints");
        constraintsCfg.setVisible(false);
        constraintsCfg.setCanBeConsumed(false);
        constraintsCfg.setCanBeResolved(false);
        constraintsCfg.setDescription("Same-version constraints for modules in the same group");
        return constraintsCfg;
    }

    private void addSiblingProjectConstraints(Project project, Set<Project> siblings, Configuration constraintsCfg) {
        siblings.stream().filter(sibling -> !sibling.equals(project)).forEach(sibling -> {
            DependencyConstraint constraint = createSiblingConstraint(project, sibling);
            constraintsCfg.getDependencyConstraints().add(constraint);
        });
    }

    private DependencyConstraint createSiblingConstraint(Project project, Project sibling) {
        String gavNoVersion = sibling.getGroup() + ":" + sibling.getName();
        String version = String.valueOf(sibling.getVersion());

        return project.getDependencies().getConstraints().create(gavNoVersion, c -> {
            c.version(v -> v.require("[" + version + ",)"));
            c.because("Align modules from the same repository to the same version");
        });
    }

    private void applyConstraintsToJavaConfigurations(Project project, Configuration constraintsCfg) {
        project.getPluginManager().withPlugin("java", _plugin -> {
            extendConfiguration(project, JavaPlugin.API_ELEMENTS_CONFIGURATION_NAME, constraintsCfg);
            extendConfiguration(project, JavaPlugin.RUNTIME_ELEMENTS_CONFIGURATION_NAME, constraintsCfg);
        });
    }

    private void extendConfiguration(Project project, String configurationName, Configuration constraintsCfg) {
        project.getConfigurations().named(configurationName).configure(conf -> conf.extendsFrom(constraintsCfg));
    }

    private static boolean isJavaLibrary(Project project) {
        if (project.getPluginManager().hasPlugin("nebula.maven-publish")) {
            // 'nebula.maven-publish' creates publications lazily which causes inconsistencies based
            // on ordering.
            log.debug(
                    "Project '{}' is considered a library because the 'nebula.maven-publish' plugin is applied",
                    project.getDisplayName());
            return true;
        }
        PublishingExtension publishing = project.getExtensions().findByType(PublishingExtension.class);
        if (publishing == null) {
            log.debug(
                    "Project '{}' is considered a distribution, not a library, because "
                            + "it doesn't define any publishing extensions",
                    project.getDisplayName());
            return false;
        }
        ImmutableList<String> jarPublications = publishing.getPublications().stream()
                .filter(pub -> isLibraryPublication(project, pub))
                .map(Named::getName)
                .collect(ImmutableList.toImmutableList());
        if (jarPublications.isEmpty()) {
            log.debug(
                    "Project '{}' is not considered a library because it does not publish jars",
                    project.getDisplayName());
            return false;
        }
        log.debug(
                "Project '{}' is considered a library because it publishes jars: {}",
                project.getDisplayName(),
                jarPublications);
        return true;
    }

    private static boolean isLibraryPublication(Project project, Publication publication) {
        if (publication instanceof MavenPublication mavenPublication) {

            return mavenPublication.getArtifacts().stream().anyMatch(artifact -> "jar".equals(artifact.getExtension()));
        }
        if (publication instanceof IvyPublication ivyPublication) {

            return ivyPublication.getArtifacts().stream().anyMatch(artifact -> "jar".equals(artifact.getExtension()));
        }
        log.warn(
                "Unknown publication '{}' of type '{}'. Assuming project {} is a library",
                publication,
                publication.getClass().getName(),
                project.getName());
        return true;
    }

    // Add this functional interface at the top of the class or as a nested interface
    @FunctionalInterface
    interface DependencyConstraintCreator {
        DependencyConstraint create(Object dependencyNotation, Action<? super DependencyConstraint> configureAction);
    }
}
