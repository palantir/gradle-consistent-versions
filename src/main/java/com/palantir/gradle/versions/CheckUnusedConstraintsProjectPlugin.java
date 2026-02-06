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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.inject.Inject;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.result.ResolvedComponentResult;
import org.gradle.api.attributes.Usage;
import org.gradle.api.file.FileCollection;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.TaskProvider;

public abstract class CheckUnusedConstraintsProjectPlugin implements Plugin<Project> {

    static final String USAGE = "gcv-check-unused-constraints";

    @Inject
    protected abstract TaskContainer getTasks();

    @Inject
    protected abstract ObjectFactory getObjectFactory();

    @Inject
    protected abstract ProviderFactory getProviderFactory();

    @Inject
    protected abstract ConfigurationContainer getConfigurations();

    @Override
    public final void apply(Project project) {
        Provider<List<Configuration>> configurationsToCheck = getProviderFactory()
                .provider(() -> GradleConfigurations.getResolvableConfigurations(project).stream()
                        .filter(config -> !config.getName().startsWith("checkUnusedConstraints"))
                        .toList());

        Provider<List<FileCollection>> resolvedFiles =
                configurationsToCheck.map(configurations -> configurations.stream()
                        .map(config -> config.getIncoming()
                                .artifactView(view -> view.lenient(true))
                                .getFiles())
                        .toList());

        Provider<Map<String, ResolvedComponentResult>> rootComponents = configurationsToCheck.map(configurations ->
                configurations.stream().collect(Collectors.toMap(configuration -> configuration.getName(), configuration -> configuration.getIncoming()
                        .getResolutionResult()
                        .getRoot())));

        TaskProvider<WriteResolvedCoordinatesTask> writeResolvedCoordinatesTask = getTasks()
                .register("writeResolvedCoordinatesTask", WriteResolvedCoordinatesTask.class, task -> {
                    task.getOutputFile()
                            .fileValue(new File(task.getTemporaryDir(), "resolved-module-identifiers.json"));
                    task.getResolvedFiles().from(resolvedFiles);
                    task.getRootComponents().putAll(rootComponents);
                });

        getConfigurations().register("checkUnusedConstraintsConsumable", consumable -> {
            consumable.setCanBeConsumed(true);
            consumable.setCanBeResolved(false);
            consumable.setVisible(false);
            consumable.setTransitive(false);
            consumable.attributes(attrs -> {
                attrs.attribute(Usage.USAGE_ATTRIBUTE, getObjectFactory().named(Usage.class, USAGE));
            });

            consumable
                    .getOutgoing()
                    .artifact(writeResolvedCoordinatesTask.flatMap(WriteResolvedCoordinatesTask::getOutputFile));
        });
    }
}
