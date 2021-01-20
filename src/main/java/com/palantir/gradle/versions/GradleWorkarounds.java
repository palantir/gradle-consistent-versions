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

import groovy.lang.GString;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.LinkedHashSet;
import java.util.Set;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.ProjectState;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ProjectDependency;
import org.gradle.api.artifacts.dsl.DependencyConstraintHandler;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.attributes.Attribute;
import org.gradle.api.attributes.AttributeContainer;
import org.gradle.api.attributes.Category;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.ListProperty;
import org.gradle.util.GradleVersion;

@SuppressWarnings("UnstableApiUsage")
final class GradleWorkarounds {
    private static final Logger log = Logging.getLogger(GradleWorkarounds.class);

    private static final GradleVersion GRADLE_VERSION_CATEGORY_AVAILABLE = GradleVersion.version("5.3-rc-1");

    /**
     * Copied from {@code org.gradle.api.internal.artifacts.dsl.dependencies.PlatformSupport#COMPONENT_CATEGORY} since
     * that's internal. This is only meant to be used with gradle < {@link #GRADLE_VERSION_CATEGORY_AVAILABLE}
     */
    private static final Attribute<String> OLD_COMPONENT_CATEGORY =
            Attribute.of("org.gradle.component.category", String.class);

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
     * Allow a {@link ListProperty} to be used with {@link DomainObjectCollection#addAllLater}.
     *
     * <p>Pending fix: https://github.com/gradle/gradle/pull/10288
     */
    @SuppressWarnings("unchecked")
    static <T> ListProperty<T> fixListProperty(ListProperty<T> property) {
        Class<?> propertyInternalClass = org.gradle.api.internal.provider.CollectionPropertyInternal.class;
        return (ListProperty<T>) Proxy.newProxyInstance(
                GradleWorkarounds.class.getClassLoader(),
                new Class<?>[] {org.gradle.api.internal.provider.CollectionProviderInternal.class, ListProperty.class},
                (_proxy, method, args) -> {
                    // Find matching method on CollectionPropertyInternal
                    // org.gradle.api.internal.provider.CollectionProviderInternal
                    if (method.getDeclaringClass()
                            == org.gradle.api.internal.provider.CollectionProviderInternal.class) {
                        if (method.getName().equals("getElementType")) {
                            // Proxy to `propertyInternalClass` which we know DefaultListProperty implements.
                            return propertyInternalClass
                                    .getMethod(method.getName(), method.getParameterTypes())
                                    .invoke(property, args);
                        } else if (method.getName().equals("size")) {
                            return property.get().size();
                        }
                        throw new GradleException(String.format(
                                "Could not proxy method '%s' to object %s", method, property.getOrNull()));
                    } else {
                        return method.invoke(property, args);
                    }
                });
    }

    /**
     * Returns whether a dependency / component is a non-enforced platform, i.e. what you create with
     * {@link DependencyHandler#platform} or {@link DependencyConstraintHandler#platform}.
     */
    static boolean isPlatform(AttributeContainer attributes) {
        if (GradleVersion.current().compareTo(GRADLE_VERSION_CATEGORY_AVAILABLE) < 0) {
            return isPlatformPre53(attributes);
        }
        return isPlatformPost53(attributes);
    }

    private static boolean isPlatformPost53(AttributeContainer attributes) {
        Category category = attributes.getAttribute(Category.CATEGORY_ATTRIBUTE);
        return category != null && Category.REGULAR_PLATFORM.equals(category.getName());
    }

    private static boolean isPlatformPre53(AttributeContainer attributes) {
        String category = attributes.getAttribute(OLD_COMPONENT_CATEGORY);
        return category != null && category.equals("platform");
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
                || rootProject.getGradle().getStartParameter().isWriteDependencyLocks()
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
                    if (dependency instanceof ProjectDependency) {
                        Project dependencyProject = ((ProjectDependency) dependency).getDependencyProject();
                        if (dependencyProject != rootProject) {
                            projectPathsToEval.add(dependencyProject.getPath());
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
