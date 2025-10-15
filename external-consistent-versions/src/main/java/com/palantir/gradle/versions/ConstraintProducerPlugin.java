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
import java.util.Set;
import java.util.stream.Collectors;
import org.gradle.api.Named;
import org.gradle.api.NamedDomainObjectProvider;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.publish.Publication;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.publish.ivy.IvyPublication;
import org.gradle.api.publish.maven.MavenPublication;

public class ConstraintProducerPlugin implements Plugin<Project> {

    static final String VIRTUAL_PLATFORM_PREFIX = "consistent-versions.external-virtual-platform";

    @Override
    public final void apply(Project rootProject) {
        if (!rootProject.equals(rootProject.getRootProject())) {
            throw new IllegalStateException("ConstraintProducerPlugin must be applied to the root project");
        }

        rootProject.afterEvaluate(project -> {
            project.getAllprojects().stream()
                    .filter(ConstraintProducerPlugin::isJavaLibrary)
                    .collect(Collectors.groupingBy(proj -> String.valueOf(proj.getGroup()), Collectors.toSet()))
                    .forEach((groupName, projects) -> {
                        if (projects.size() > 1) {
                            applyVirtualPlatformPerGroup(groupName, projects);
                        }
                    });
        });
    }

    private void applyVirtualPlatformPerGroup(String groupName, Set<Project> projectGroup) {
        String platformCoordinates = VIRTUAL_PLATFORM_PREFIX + "." + groupName + ":_";

        projectGroup.stream()
                .filter(project -> project.getPluginManager().hasPlugin("java"))
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
                                constraint.because("Virtual platform for version alignment across group when using "
                                        + "com.palantir.externally-consistent-versions");
                            }));
                });

        project.getConfigurations()
                .named(JavaPlugin.API_ELEMENTS_CONFIGURATION_NAME)
                .configure(conf -> conf.extendsFrom(constraints.get()));

        project.getConfigurations()
                .named(JavaPlugin.RUNTIME_ELEMENTS_CONFIGURATION_NAME)
                .configure(conf -> conf.extendsFrom(constraints.get()));
    }

    static boolean isJavaLibrary(Project project) {
        if (project.getPluginManager().hasPlugin("nebula.maven-publish")) {
            // 'nebula.maven-publish' creates publications lazily which causes inconsistencies based
            // on ordering.
            return true;
        }
        PublishingExtension publishing = project.getExtensions().findByType(PublishingExtension.class);
        if (publishing == null) {
            return false;
        }
        ImmutableList<String> jarPublications = publishing.getPublications().stream()
                .filter(ConstraintProducerPlugin::isLibraryPublication)
                .map(Named::getName)
                .collect(ImmutableList.toImmutableList());
        return !jarPublications.isEmpty();
    }

    private static boolean isLibraryPublication(Publication publication) {
        if (publication instanceof MavenPublication mavenPublication) {
            return mavenPublication.getArtifacts().stream().anyMatch(artifact -> "jar".equals(artifact.getExtension()));
        }
        if (publication instanceof IvyPublication ivyPublication) {
            return ivyPublication.getArtifacts().stream().anyMatch(artifact -> "jar".equals(artifact.getExtension()));
        }
        return true;
    }
}
