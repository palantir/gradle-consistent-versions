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

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.inject.Inject;
import org.gradle.api.NamedDomainObjectProvider;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.ProjectDependency;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.attributes.Usage;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.tasks.TaskProvider;

public abstract class CheckUnusedConstraintsProjectPlugin implements Plugin<Project> {

    static final String OUTGOING_USAGE = "check-unused-constraints-module-identifiers";
    static final String TASK_NAME = "writeResolvedModulesTask";

    @Inject
    protected abstract ProjectLayout getLayout();

    @Inject
    protected abstract ObjectFactory getObjectFactory();

    @Inject
    protected abstract ConfigurationContainer getConfigurations();

    @Inject
    protected abstract DependencyHandler getDependencyHandler();

    @Override
    public final void apply(Project project) {
        // Create an incoming configuration that will resolve artifacts from dependent projects.
        // This establishes proper task ordering through Gradle's dependency resolution system.
        NamedDomainObjectProvider<Configuration> incomingDependentProjectModules = getConfigurations()
                .register("checkUnusedConstraintsIncoming", incoming -> {
                    incoming.setCanBeConsumed(false);
                    incoming.setCanBeResolved(true);
                    incoming.setVisible(false);
                    incoming.setTransitive(false);
                    incoming.attributes(attrs -> {
                        attrs.attribute(
                                Usage.USAGE_ATTRIBUTE, getObjectFactory().named(Usage.class, OUTGOING_USAGE));
                    });

                    // Lazily populate with project dependencies found in resolvable configs.
                    // withDependencies is only called when the configuration is resolved.
                    incoming.withDependencies(deps -> {
                        getDependentProjectPaths(project)
                                .forEach(path -> deps.add(getDependencyHandler().project(Map.of("path", path))));
                    });
                });

        TaskProvider<WriteResolvedModulesTask> writeResolvedModulesTask = project.getTasks()
                .register(TASK_NAME, WriteResolvedModulesTask.class, task -> {
                    task.getOutputFile()
                            .set(getLayout()
                                    .getBuildDirectory()
                                    .file("tmp/check-unused-constraints/resolved-module-identifiers.json"));

                    task.getResolvableConfigurationNames()
                            .set(project.provider(
                                    () -> GradleConfigurations.getResolvableConfigurations(project).stream()
                                            .map(Configuration::getName)
                                            .collect(Collectors.toSet())));

                    task.getDependentProjectModuleFiles()
                            .from(incomingDependentProjectModules.map(
                                    config -> config.getIncoming().getFiles()));
                });

        getConfigurations().register("check-unused-constraints-outgoing", outgoing -> {
            outgoing.setCanBeConsumed(true);
            outgoing.setCanBeResolved(false);
            outgoing.setVisible(false);
            outgoing.attributes(attrs -> {
                attrs.attribute(Usage.USAGE_ATTRIBUTE, getObjectFactory().named(Usage.class, OUTGOING_USAGE));
            });

            outgoing.getOutgoing().artifact(writeResolvedModulesTask.flatMap(WriteResolvedModulesTask::getOutputFile));
        });
    }

    /**
     * Returns the set of project paths that this project depends on, excluding ancestor projects.
     *
     * <p>We filter out ancestor projects to avoid circular dependencies. The VersionsLockPlugin adds
     * project(":") to standard Java configurations (compileClasspath, runtimeClasspath, etc.) on all
     * projects as part of the unified classpath mechanism. We must filter these out to avoid cycles.
     */
    private static Set<String> getDependentProjectPaths(Project project) {
        String currentProjectPath = project.getPath();
        return GradleConfigurations.getResolvableConfigurations(project).stream()
                .flatMap(config -> config.getAllDependencies().stream())
                .filter(ProjectDependency.class::isInstance)
                .map(ProjectDependency.class::cast)
                .map(ProjectDependency::getPath)
                .filter(path -> !isAncestorOrSelf(currentProjectPath, path))
                .collect(Collectors.toSet());
    }

    /**
     * Returns true if {@code otherPath} is the same as or an ancestor of {@code currentPath}.
     */
    private static boolean isAncestorOrSelf(String currentPath, String otherPath) {
        if (":".equals(otherPath)) {
            return true; // Root project is ancestor of everything
        }
        return currentPath.equals(otherPath) || currentPath.startsWith(otherPath + ":");
    }
}
