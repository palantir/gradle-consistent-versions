/*
 * (c) Copyright 2026 Palantir Technologies Inc. All rights reserved.
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

import java.io.File;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.inject.Inject;
import org.gradle.api.NamedDomainObjectProvider;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.ProjectDependency;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.artifacts.result.ResolutionResult;
import org.gradle.api.artifacts.result.ResolvedComponentResult;
import org.gradle.api.attributes.Usage;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.util.GradleVersion;

public abstract class CheckUnusedConstraintsProjectPlugin implements Plugin<Project> {

    static final String OUTGOING_USAGE = "gcv-check-unused-constraints";

    @Inject
    protected abstract ObjectFactory getObjectFactory();

    @Inject
    protected abstract ProviderFactory getProviderFactory();

    @Inject
    protected abstract ConfigurationContainer getConfigurations();

    @Inject
    protected abstract DependencyHandler getDependencyHandler();

    @Override
    public final void apply(Project project) {
        // Create an incoming configuration that will resolve artifacts from dependent projects.
        // This establishes proper task ordering through Gradle's dependency resolution system.
        NamedDomainObjectProvider<Configuration> resolvableDependentProjectCoordinates = getConfigurations()
                .register("checkUnusedConstraintsResolvable", resolvable -> {
                    resolvable.setCanBeConsumed(false);
                    resolvable.setCanBeResolved(true);
                    resolvable.setVisible(false);
                    resolvable.setTransitive(false);
                    resolvable.attributes(attrs -> {
                        attrs.attribute(
                                Usage.USAGE_ATTRIBUTE, getObjectFactory().named(Usage.class, OUTGOING_USAGE));
                    });

                    resolvable.withDependencies(deps -> {
                        getDependentProjectPaths(project)
                                .forEach(path -> deps.add(getDependencyHandler().project(Map.of("path", path))));
                    });
                });

        TaskProvider<WriteResolvedCoordinatesTask> writeResolvedCoordinatesTask = project.getTasks()
                .register("writeResolvedCoordinatesTask", WriteResolvedCoordinatesTask.class, task -> {
                    task.getOutputFile()
                            .fileValue(new File(task.getTemporaryDir(), "resolved-module-identifiers.json"));

                    task.getResolvedCoordinates()
                            .set(getProviderFactory()
                                    .provider(() -> GradleConfigurations.getResolvableConfigurations(project).stream()
                                            .flatMap(CheckUnusedConstraintsProjectPlugin::resolvedModules)
                                            .collect(Collectors.toSet())));

                    task.getDependentProjectCoordinates()
                            .from(resolvableDependentProjectCoordinates.map(config -> config.getIncoming()
                                    .artifactView(view -> view.lenient(true))
                                    .getFiles()));
                });

        getConfigurations().register("checkUnusedConstraintsConsumable", consumable -> {
            consumable.setCanBeConsumed(true);
            consumable.setCanBeResolved(false);
            consumable.setVisible(false);
            consumable.setTransitive(false);
            consumable.attributes(attrs -> {
                attrs.attribute(Usage.USAGE_ATTRIBUTE, getObjectFactory().named(Usage.class, OUTGOING_USAGE));
            });

            consumable.getOutgoing().artifact(writeResolvedCoordinatesTask.flatMap(WriteResolvedCoordinatesTask::getOutputFile));
        });
    }

    /**
     * Returns the set of project paths that this project depends on, excluding self and root projects.
     *
     * <p> We must filter these out to avoid cycles where subproject tasks depend on root project tasks.
     */
    @SuppressWarnings("deprecation")
    private static Set<String> getDependentProjectPaths(Project project) {
        return GradleConfigurations.getResolvableConfigurations(project).stream()
                .flatMap(config -> config.getAllDependencies().stream())
                .filter(ProjectDependency.class::isInstance)
                .map(ProjectDependency.class::cast)
                .map(CheckUnusedConstraintsProjectPlugin::getProjectDependencyPath)
                .filter(path -> !isRootOrSelf(project.getPath(), path))
                .collect(Collectors.toSet());
    }

    /**
     * Returns true if {@code otherPath} is the same as or is the root {@code currentPath}.
     */
    private static boolean isRootOrSelf(String currentPath, String otherPath) {
        if (":".equals(otherPath)) {
            return true; // Root project is ancestor of everything
        }
        return currentPath.equals(otherPath);
    }

    /**
     * Gets the path of a project dependency, handling API differences across Gradle versions.
     * {@link ProjectDependency#getPath} was added in Gradle 8.11, before which we must use
     * {@link ProjectDependency#getDependencyProject()}.
     */
    @SuppressWarnings("deprecation")
    private static String getProjectDependencyPath(ProjectDependency projectDependency) {
        if (GradleVersion.current().compareTo(GradleVersion.version("8.11")) < 0) {
            return projectDependency.getDependencyProject().getPath();
        }
        return projectDependency.getPath();
    }

    private static Stream<ResolvedCoordinate> resolvedModules(Configuration configuration) {
        ResolutionResult resolutionResult = configuration.getIncoming().getResolutionResult();
        try {
            return resolutionResult.getAllComponents().stream()
                    .map(ResolvedComponentResult::getId)
                    .filter(cid -> !cid.equals(resolutionResult.getRoot().getId()))
                    .filter(ModuleComponentIdentifier.class::isInstance)
                    .map(ModuleComponentIdentifier.class::cast)
                    .map(mcid -> ResolvedCoordinate.of(configuration.getName(), mcid.getGroup(), mcid.getModule()));
        } catch (Exception e) {
            throw new RuntimeException(
                    String.format("Error during resolution of the dependency graph of configuration %s", configuration),
                    e);
        }
    }
}
