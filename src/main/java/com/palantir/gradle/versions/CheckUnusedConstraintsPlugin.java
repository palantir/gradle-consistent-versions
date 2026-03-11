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
import javax.inject.Inject;
import org.gradle.api.GradleException;
import org.gradle.api.NamedDomainObjectProvider;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.attributes.Usage;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.TaskProvider;

public abstract class CheckUnusedConstraintsPlugin implements Plugin<Project> {

    @Inject
    protected abstract TaskContainer getTasks();

    @Inject
    protected abstract ProjectLayout getLayout();

    @Inject
    protected abstract ObjectFactory getObjectFactory();

    @Inject
    protected abstract ProviderFactory getProviderFactory();

    @Inject
    protected abstract DependencyHandler getDependencyHandler();

    @Inject
    protected abstract ConfigurationContainer getConfigurations();

    @Override
    public final void apply(Project rootProject) {
        if (rootProject != rootProject.getRootProject()) {
            throw new GradleException("The CheckUnusedConstraintsPlugin plugin must be applied on the root project");
        }

        rootProject.allprojects(subproject -> {
            subproject.getPluginManager().apply(CheckUnusedConstraintsProjectPlugin.class);
        });

        NamedDomainObjectProvider<Configuration> resolvableCoordinates = getConfigurations()
                .register("checkUnusedConstraintsResolvable", resolvable -> {
                    resolvable.setCanBeConsumed(false);
                    resolvable.setCanBeResolved(true);
                    resolvable.setTransitive(false);
                    resolvable.setVisible(false);
                    resolvable.attributes(attributes -> {
                        attributes.attribute(
                                Usage.USAGE_ATTRIBUTE,
                                getObjectFactory().named(Usage.class, CheckUnusedConstraintsProjectPlugin.USAGE));
                    });

                    resolvable
                            .getDependencies()
                            .addAll(rootProject.getAllprojects().stream()
                                    .map(subproject ->
                                            getDependencyHandler().project(Map.of("path", subproject.getPath())))
                                    .toList());
                });

        TaskProvider<CheckUnusedConstraintsTask> checkUnusedConstraintsTask = getTasks()
                .register("checkUnusedConstraints", CheckUnusedConstraintsTask.class, task -> {
                    task.getResolvedCoordinatesFiles()
                            .from(resolvableCoordinates.map(resolvable -> resolvable
                                    .getIncoming()
                                    .artifactView(view -> view.lenient(true))
                                    .getFiles()));

                    task.getExcludeConfigurations()
                            .set(rootProject
                                    .getExtensions()
                                    .getByType(VersionRecommendationsExtension.class)
                                    .getExcludeConfigurations());

                    task.getPropsFile().set(getLayout().getProjectDirectory().file("versions.props"));
                });

        getTasks().named("check").configure(task -> task.dependsOn(checkUnusedConstraintsTask));
    }
}
