/*
 * (c) Copyright 2019 Palantir Technologies Inc. All rights reserved.
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

import com.palantir.gradle.utils.projectdependency.ProjectDependencyUtils;
import groovy.lang.GString;
import java.lang.reflect.InvocationTargetException;
import java.util.LinkedHashSet;
import java.util.Set;
import org.gradle.api.Project;
import org.gradle.api.ProjectState;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ProjectDependency;
import org.gradle.api.artifacts.dsl.DependencyConstraintHandler;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.attributes.AttributeContainer;
import org.gradle.api.attributes.Category;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;

@SuppressWarnings("UnstableApiUsage")
final class GradleWorkarounds {
    private static final Logger log = Logging.getLogger(GradleWorkarounds.class);

    /** Check if the project is still in the "configuring" stage, i.e. before or including afterEvaluate. */
    static boolean isConfiguring(ProjectState state) {
        try {
            Class<?> stateInternal = Class.forName("org.gradle.api.internal.project.ProjectStateInternal");
            Object internal = stateInternal.cast(state);
            return (boolean) stateInternal.getDeclaredMethod("isConfiguring").invoke(internal);
        } catch (ClassNotFoundException
                | ClassCastException
                | NoSuchMethodException
                | IllegalAccessException
                | InvocationTargetException e) {
            log.warn("Couldn't use ProjectStateInternal to determine whether project is configuring", e);
            // This is an approximation the public API exposes.
            // It will give us a false negative if we're in 'afterEvaluate'
            return !state.getExecuted();
        }
    }

    /**
     * Returns whether a dependency / component is a non-enforced platform, i.e. what you create with
     * {@link DependencyHandler#platform} or {@link DependencyConstraintHandler#platform}.
     */
    @SuppressWarnings("for-rollout:InvalidLink")
    static boolean isPlatform(AttributeContainer attributes) {
        Category category = attributes.getAttribute(Category.CATEGORY_ATTRIBUTE);
        return category != null && Category.REGULAR_PLATFORM.equals(category.getName());
    }

    static boolean isFailOnVersionConflict(Configuration conf) {
        org.gradle.api.internal.artifacts.configurations.ConflictResolution conflictResolution =
                ((org.gradle.api.internal.artifacts.configurations.ResolutionStrategyInternal)
                                conf.getResolutionStrategy())
                        .getConflictResolution();
        return conflictResolution == org.gradle.api.internal.artifacts.configurations.ConflictResolution.strict;
    }

    @SuppressWarnings("CyclomaticComplexity")
    public static void makeEvaluationDependOnSubprojectsToBeEvaluated(Project rootProject) {
        if (!rootProject.getGradle().getStartParameter().isConfigureOnDemand()
                || VersionsLockPlugin.shouldWriteLocks(rootProject)
                // If Gradle is run from somewhere other than the root, the task location gets trickier to translate
                // into the projects to use; this could be implemented in the future if there's demand
                || !rootProject.getGradle().getStartParameter().getCurrentDir().equals(rootProject.getRootDir())) {
            // Just configure every project
            rootProject.getSubprojects().forEach(subproject -> rootProject.evaluationDependsOn(subproject.getPath()));
            return;
        }

        Set<String> projectPathsToEval = new LinkedHashSet<>();
        for (String taskPath : rootProject.getGradle().getStartParameter().getTaskNames()) {
            if (!taskPath.contains(":")) {
                // This is a task to be run in each project that defines it, e.g. "build". This should cause every
                // project to be defined in a configuration-on-demand build.
                rootProject
                        .getSubprojects()
                        .forEach(subproject -> rootProject.evaluationDependsOn(subproject.getPath()));
                return;
            }
            String projectPath = taskPath.substring(0, taskPath.lastIndexOf(':'));
            if (!projectPath.startsWith(":")) {
                projectPath = ":" + projectPath;
            }
            projectPathsToEval.add(projectPath);
        }

        Set<String> alreadyVisited = new LinkedHashSet<>();
        while (!projectPathsToEval.isEmpty()) {
            String projectPath = projectPathsToEval.iterator().next();
            projectPathsToEval.remove(projectPath);
            if (alreadyVisited.contains(projectPath) || projectPath.equals(":")) {
                continue;
            }

            rootProject.evaluationDependsOn(projectPath);
            Project project = rootProject.project(projectPath);
            if (!project.getState().getExecuted()) {
                throw new IllegalStateException(
                        "The project has not yet been evaluated when we expect it to have been");
            }
            // Per
            // https://docs.gradle.org/current/userguide/multi_project_configuration_and_execution.html#sec:configuration_on_demand,
            // configuration is propagated transitively in two ways: dependencies and string-based task dependencies.
            for (Configuration configuration : project.getConfigurations()) {
                for (Dependency dependency : configuration.getDependencies()) {
                    if (dependency instanceof ProjectDependency projectDependency) {
                        String dependencyProjectPath = ProjectDependencyUtils.getProjectPath(projectDependency);
                        if (!dependencyProjectPath.equals(rootProject.getPath())) {
                            projectPathsToEval.add(dependencyProjectPath);
                        }
                    }
                }
            }
            // This may pull in additional projects due to tasks we aren't executing, but it shouldn't be too surprising
            // to configure projects that are "upstream" in any sense.
            for (Task task : project.getTasks()) {
                for (Object dependedOnObj : task.getDependsOn()) {
                    if (dependedOnObj instanceof String || dependedOnObj instanceof GString) {
                        String dependedOnTaskPath = dependedOnObj.toString();
                        if (dependedOnTaskPath.contains(":")) {
                            String dependencyProjectPath =
                                    dependedOnTaskPath.substring(0, dependedOnTaskPath.lastIndexOf(':'));
                            if (!dependencyProjectPath.isEmpty()) {
                                projectPathsToEval.add(dependencyProjectPath);
                            }
                        }
                    }
                }
            }

            alreadyVisited.add(projectPath);
        }
    }

    private GradleWorkarounds() {}
}
