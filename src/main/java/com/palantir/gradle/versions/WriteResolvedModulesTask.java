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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.result.ResolutionResult;
import org.gradle.api.artifacts.result.ResolvedComponentResult;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

public abstract class WriteResolvedModulesTask extends DefaultTask {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Inject
    protected abstract ConfigurationContainer getConfigurationContainer();

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    @Input
    public abstract SetProperty<String> getResolvableConfigurationNames();

    /**
     * Files from dependent projects' writeResolvedModulesTask.
     * This input establishes task ordering to prevent parallel resolution lock errors
     * when resolving configurations that have cross-project dependencies.
     * The actual file contents are not used; this is purely for ordering.
     */
    @InputFiles
    @PathSensitive(PathSensitivity.NONE)
    public abstract ConfigurableFileCollection getDependentProjectModuleFiles();

    @TaskAction
    public final void writeResolvedModules() {
        try {
            OBJECT_MAPPER.writeValue(
                    getOutputFile().get().getAsFile(),
                    configurations().stream()
                            .flatMap(WriteResolvedModulesTask::getResolvedModules)
                            .collect(Collectors.toSet()));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write resolved module identifiers", e);
        }
    }

    private Set<Configuration> configurations() {
        return getResolvableConfigurationNames().get().stream()
                .map(getConfigurationContainer()::findByName)
                .filter(Objects::nonNull)
                .collect(ImmutableSet.toImmutableSet());
    }

    private static Stream<ResolvedModule> getResolvedModules(Configuration configuration) {
        ResolutionResult resolutionResult = configuration.getIncoming().getResolutionResult();
        try {
            return resolutionResult.getAllComponents().stream()
                    .map(ResolvedComponentResult::getId)
                    .filter(cid -> !cid.equals(resolutionResult.getRoot().getId()))
                    .filter(ModuleComponentIdentifier.class::isInstance)
                    .map(ModuleComponentIdentifier.class::cast)
                    .map(mcid -> ResolvedModule.of(configuration.getName(), mcid.getGroup() + ":" + mcid.getModule()));
        } catch (Exception e) {
            throw new RuntimeException(
                    String.format("Error during resolution of the dependency graph of configuration %s", configuration),
                    e);
        }
    }
}