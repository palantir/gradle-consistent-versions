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
import org.gradle.api.GradleException;
import org.gradle.api.NamedDomainObjectProvider;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.attributes.Usage;
import org.gradle.api.tasks.TaskProvider;

public abstract class CheckUnusedConstraintsPlugin implements Plugin<Project> {

    @Override
    public final void apply(Project rootProject) {
        if (rootProject != rootProject.getRootProject()) {
            throw new GradleException("The CheckUnusedConstraintsPlugin plugin must be applied on the root project");
        }

        VersionRecommendationsExtension extension =
                rootProject.getExtensions().getByType(VersionRecommendationsExtension.class);

        NamedDomainObjectProvider<Configuration> subprojectDependencies = rootProject
                .getConfigurations()
                .register("checkUnusedConstraintsSubprojects", conf -> {
                    conf.setCanBeConsumed(false);
                    conf.setCanBeResolved(false);
                });

        rootProject.allprojects(subproject -> {
            subproject.getPlugins().apply(CheckUnusedConstraintsProjectPlugin.class);
        });

        subprojectDependencies.configure(subprojectDeps -> {
            subprojectDeps
                    .getDependencies()
                    .addAllLater(rootProject.provider(() -> rootProject.getAllprojects().stream()
                            .map(subproject ->
                                    rootProject.getDependencies().project(Map.of("path", subproject.getPath())))
                            .toList()));
        });

        NamedDomainObjectProvider<Configuration> collectedConfiguration = rootProject
                .getConfigurations()
                .register("collectedCheckUnusedConstraintsOutgoing", conf -> {
                    conf.setCanBeConsumed(false);
                    conf.setCanBeResolved(true);
                    conf.extendsFrom(subprojectDependencies.get());
                    conf.setTransitive(false);
                    conf.attributes(attrs -> {
                        attrs.attribute(
                                Usage.USAGE_ATTRIBUTE,
                                rootProject
                                        .getObjects()
                                        .named(Usage.class, CheckUnusedConstraintsProjectPlugin.OUTGOING_USAGE));
                    });
                });

        TaskProvider<CheckUnusedConstraintsTask> checkNoUnusedConstraints = rootProject
                .getTasks()
                .register("checkUnusedConstraints", CheckUnusedConstraintsTask.class, task -> {
                    task.getResolvedModulesFiles()
                            .from(collectedConfiguration.map(
                                    resolvable -> resolvable.getIncoming().getFiles()));

                    task.getExcludeConfigurations().set(extension.getExcludeConfigurations());

                    task.getPropsFile()
                            .set(rootProject.getLayout().getProjectDirectory().file("versions.props"));
                });

        rootProject.getTasks().named("check").configure(task -> task.dependsOn(checkNoUnusedConstraints));
    }
}
