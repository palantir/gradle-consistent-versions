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

import com.palantir.gradle.versions.VersionsLockPlugin.ProjectDependencyWorkarounds;
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

        ProjectDependencyWorkarounds projectDependencyWorkarounds =
                rootProject.getObjects().newInstance(ProjectDependencyWorkarounds.class);

        // Projects that need to be evaluated and have their configurations scanned for project dependencies.
        Set<String> projectPathsToEval = new LinkedHashSet<>();
        // Specific task paths (e.g. ":foo:bar") whose dependsOn we need to walk. We only realize these tasks,
        // never the whole TaskContainer — iterating project.getTasks() forces realization of every lazy
        // TaskProvider, which puts the cross-project mutation guard into "lazy context" and downstream rejects
        // Project#afterEvaluate(...) calls made from third-party plugins applied during subproject evaluation
        // (e.g. callbacks registered via PluginManager#withPlugin).
        Set<String> taskPathsToWalk = new LinkedHashSet<>();
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
            String taskName = taskPath.substring(taskPath.lastIndexOf(':') + 1);
            if (projectPath.isEmpty() || projectPath.equals(":")) {
                // Root-level task like ":build". Root is already evaluated; just walk its dependsOn.
                taskPathsToWalk.add(":" + taskName);
            } else {
                if (!projectPath.startsWith(":")) {
                    projectPath = ":" + projectPath;
                }
                projectPathsToEval.add(projectPath);
                taskPathsToWalk.add(projectPath + ":" + taskName);
            }
        }

        Set<String> alreadyVisitedProjects = new LinkedHashSet<>();
        // Root is always evaluated already (we're called from its afterEvaluate). Mark it visited so we never
        // try to evaluationDependsOn(":") and so root-level dependsOn walks don't infinite-loop scheduling it.
        alreadyVisitedProjects.add(":");
        Set<String> alreadyWalkedTasks = new LinkedHashSet<>();
        // Per
        // https://docs.gradle.org/current/userguide/multi_project_configuration_and_execution.html#sec:configuration_on_demand,
        // configuration is propagated transitively in two ways: ProjectDependencies and string-based task
        // dependencies. We process projects (for ProjectDependency propagation) and individual tasks (for string
        // task dependency propagation) until neither set has new work. We poll one item at a time so that
        // modifications to either set (made while processing a task's dependsOn or a project's configurations)
        // don't trigger ConcurrentModificationException.
        while (!projectPathsToEval.isEmpty() || !taskPathsToWalk.isEmpty()) {
            // Drain projects first so that the tasks we walk have their owning project already evaluated.
            while (!projectPathsToEval.isEmpty()) {
                String projectPath = projectPathsToEval.iterator().next();
                projectPathsToEval.remove(projectPath);
                if (!alreadyVisitedProjects.add(projectPath)) {
                    continue;
                }

                rootProject.evaluationDependsOn(projectPath);
                Project project = rootProject.project(projectPath);
                if (!project.getState().getExecuted()) {
                    throw new IllegalStateException(
                            "The project has not yet been evaluated when we expect it to have been");
                }
                for (Configuration configuration : project.getConfigurations()) {
                    for (Dependency dependency : configuration.getDependencies()) {
                        if (dependency instanceof ProjectDependency projectDependency) {
                            Project dependencyProject =
                                    projectDependencyWorkarounds.getDependencyProject(projectDependency);
                            if (dependencyProject != rootProject
                                    && !alreadyVisitedProjects.contains(dependencyProject.getPath())) {
                                projectPathsToEval.add(dependencyProject.getPath());
                            }
                        }
                    }
                }
            }

            // Walk one task at a time. Polling, rather than iterating, lets us safely add to taskPathsToWalk and
            // projectPathsToEval while processing the task. If the task's owning project hasn't been evaluated
            // yet, we re-queue the task and loop back to the project drain above.
            if (!taskPathsToWalk.isEmpty()) {
                String taskPath = taskPathsToWalk.iterator().next();
                taskPathsToWalk.remove(taskPath);
                if (!alreadyWalkedTasks.add(taskPath)) {
                    continue;
                }
                int lastColon = taskPath.lastIndexOf(':');
                String projectPath = lastColon == 0 ? ":" : taskPath.substring(0, lastColon);
                if (!alreadyVisitedProjects.contains(projectPath)) {
                    // Re-queue this task and ensure its owning project is on the eval queue.
                    taskPathsToWalk.add(taskPath);
                    alreadyWalkedTasks.remove(taskPath);
                    projectPathsToEval.add(projectPath);
                    continue;
                }

                String taskName = taskPath.substring(lastColon + 1);
                Project project = projectPath.equals(":") ? rootProject : rootProject.project(projectPath);
                // findByName realizes only this single named task (if it was registered lazily). We never call
                // project.getTasks().iterator() or any other method that would force-realize every task in the
                // container.
                Task task = project.getTasks().findByName(taskName);
                if (task == null) {
                    continue;
                }
                for (Object dependedOnObj : task.getDependsOn()) {
                    if (dependedOnObj instanceof String || dependedOnObj instanceof GString) {
                        String dependedOnTaskPath = dependedOnObj.toString();
                        if (dependedOnTaskPath.contains(":")) {
                            int depLastColon = dependedOnTaskPath.lastIndexOf(':');
                            String dependencyProjectPath =
                                    depLastColon == 0 ? ":" : dependedOnTaskPath.substring(0, depLastColon);
                            if (!dependencyProjectPath.startsWith(":")) {
                                dependencyProjectPath = ":" + dependencyProjectPath;
                            }
                            String normalizedTaskPath = (dependencyProjectPath.equals(":") ? "" : dependencyProjectPath)
                                    + ":" + dependedOnTaskPath.substring(depLastColon + 1);
                            if (!alreadyWalkedTasks.contains(normalizedTaskPath)) {
                                taskPathsToWalk.add(normalizedTaskPath);
                            }
                            if (!alreadyVisitedProjects.contains(dependencyProjectPath)) {
                                projectPathsToEval.add(dependencyProjectPath);
                            }
                        }
                    }
                }
            }
        }
    }

    private GradleWorkarounds() {}
}
