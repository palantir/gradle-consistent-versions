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
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.tasks.TaskProvider;

public abstract class CheckUnusedConstraintsProjectPlugin implements Plugin<Project> {

    static final String OUTGOING_USAGE = "check-unused-constraints-module-identifiers";
    static final String TASK_NAME = "writeResolvedModulesTask";

    // Internal configurations that should not be scanned for project dependencies
    // to avoid circular dependency issues
    private static final Set<String> INTERNAL_CONFIGURATION_NAMES = Set.of(
            "checkUnusedConstraintsIncoming",
            "checkUnusedConstraintsSubprojects",
            "collectedCheckUnusedConstraintsOutgoing",
            "check-unused-constraints-outgoing");

    @Inject
    protected abstract ProjectLayout getLayout();

    @Inject
    protected abstract ObjectFactory getObjectFactory();

    @Inject
    protected abstract ConfigurationContainer getConfigurations();

    @Inject
    protected abstract DependencyHandler getDependencyHandler();

    @Inject
    protected abstract ProviderFactory getProviderFactory();

    @Override
    public final void apply(Project project) {
        // Lazily compute the set of project paths this project depends on.
        // This is used both for populating the incoming configuration and for
        // deciding whether to wire up task ordering.
        Provider<Set<String>> dependentProjectPaths = getProviderFactory().provider(() -> {
            String currentProjectPath = project.getPath();
            return GradleConfigurations.getResolvableConfigurations(project).stream()
                    .filter(config -> !INTERNAL_CONFIGURATION_NAMES.contains(config.getName()))
                    .flatMap(config -> config.getAllDependencies().stream())
                    .filter(ProjectDependency.class::isInstance)
                    .map(ProjectDependency.class::cast)
                    .map(ProjectDependency::getPath)
                    // Filter out current project and ancestor projects to avoid circular dependencies.
                    // Subprojects often depend on parent projects (e.g., for platforms/BOMs), but we
                    // shouldn't wire up task dependencies for ancestors since their resolved modules
                    // aren't relevant to this project's unused constraint checking.
                    .filter(path -> !isAncestorOrSelf(currentProjectPath, path))
                    .collect(Collectors.toSet());
        });

        // Create an incoming configuration that will resolve artifacts from dependent projects.
        // This establishes proper task ordering through Gradle's dependency resolution system,
        // which is isolated-projects compatible.
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
                });

        // Populate the incoming configuration with project dependencies found in resolvable configs.
        incomingDependentProjectModules.configure(incoming -> {
            incoming.withDependencies(deps -> {
                dependentProjectPaths
                        .get()
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

                    // Only wire up the incoming configuration files as an input if there are
                    // actual project dependencies. This avoids creating spurious task dependencies
                    // through Gradle's attribute-based resolution when the configuration is empty.
                    task.getDependentProjectModuleFiles().from(dependentProjectPaths.map(paths -> {
                        if (paths.isEmpty()) {
                            return project.files();
                        }
                        return incomingDependentProjectModules
                                .get()
                                .getIncoming()
                                .getFiles();
                    }));
                });

        getConfigurations().register("check-unused-constraints-outgoing", outgoing -> {
            outgoing.setCanBeConsumed(true);
            outgoing.setCanBeResolved(false);
            outgoing.setVisible(false);
            outgoing.attributes(attrs -> {
                attrs.attribute(Usage.USAGE_ATTRIBUTE, getObjectFactory().named(Usage.class, OUTGOING_USAGE));
            });

            outgoing.getOutgoing()
                    .artifact(writeResolvedModulesTask.flatMap(WriteResolvedModulesTask::getOutputFile), artifact -> {
                        artifact.builtBy(writeResolvedModulesTask);
                    });
        });
    }

    /**
     * Returns true if {@code otherPath} is the same as or an ancestor of {@code currentPath}.
     * This is used to filter out parent projects from task dependencies to avoid circular dependencies.
     *
     * <p>Examples:
     * <ul>
     *   <li>isAncestorOrSelf(":foo", ":") returns true (root is ancestor of all)</li>
     *   <li>isAncestorOrSelf(":foo:bar", ":foo") returns true</li>
     *   <li>isAncestorOrSelf(":foo", ":foo") returns true (self)</li>
     *   <li>isAncestorOrSelf(":foo", ":bar") returns false (sibling)</li>
     *   <li>isAncestorOrSelf(":foo", ":foobar") returns false (different project)</li>
     * </ul>
     */
    private static boolean isAncestorOrSelf(String currentPath, String otherPath) {
        if (":".equals(otherPath)) {
            return true; // Root project is ancestor of everything
        }
        return currentPath.equals(otherPath) || currentPath.startsWith(otherPath + ":");
    }
}
