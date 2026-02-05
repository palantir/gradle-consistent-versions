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
import org.gradle.api.tasks.TaskProvider;

public abstract class CheckUnusedConstraintsPlugin implements Plugin<Project> {

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

        NamedDomainObjectProvider<Configuration> collectedConfiguration = getConfigurations()
                .register("collectedCheckUnusedConstraintsOutgoing", collected -> {
                    collected.setCanBeConsumed(false);
                    collected.setCanBeResolved(true);
                    collected.setTransitive(false);
                    collected.setVisible(false);
                    collected.attributes(attrs -> {
                        attrs.attribute(
                                Usage.USAGE_ATTRIBUTE,
                                getObjectFactory()
                                        .named(Usage.class, CheckUnusedConstraintsProjectPlugin.OUTGOING_USAGE));
                    });

                    // withDependencies does not seem to work on Gradle 7.x.x with configuration-on-demand so use
                    // addAllLater instead
                    collected
                            .getDependencies()
                            .addAllLater(getProviderFactory().provider(() -> rootProject.getAllprojects().stream()
                                    .map(subproject ->
                                            getDependencyHandler().project(Map.of("path", subproject.getPath())))
                                    .toList()));
                });

        TaskProvider<CheckUnusedConstraintsTask> checkNoUnusedConstraints = rootProject
                .getTasks()
                .register("checkUnusedConstraints", CheckUnusedConstraintsTask.class, task -> {
                    task.getResolvedModulesFiles()
                            .from(collectedConfiguration.map(
                                    resolvable -> resolvable.getIncoming().getFiles()));

                    task.getExcludeConfigurations()
                            .set(rootProject
                                    .getExtensions()
                                    .getByType(VersionRecommendationsExtension.class)
                                    .getExcludeConfigurations());

                    task.getPropsFile().set(getLayout().getProjectDirectory().file("versions.props"));
                });

        rootProject.getTasks().named("check").configure(task -> task.dependsOn(checkNoUnusedConstraints));
    }
}
