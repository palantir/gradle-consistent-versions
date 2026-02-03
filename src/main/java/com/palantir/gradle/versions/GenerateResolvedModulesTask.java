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

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.stream.Collectors;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.result.ResolutionResult;
import org.gradle.api.artifacts.result.ResolvedComponentResult;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

public abstract class GenerateResolvedModulesTask extends DefaultTask {

    @Inject
    public GenerateResolvedModulesTask() {}

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    // Marked as @Internal because Configuration objects aren't serializable inputs.
    // The task will always re-run when resolution is needed, which is fine since
    // it's driven by artifact resolution.
    @Internal
    public abstract SetProperty<Configuration> getConfigurations();

    @TaskAction
    public final void generate() {
        String content = getConfigurations().get().stream()
                .flatMap(configuration -> {
                    String configName = configuration.getName();
                    try {
                        ResolutionResult resolutionResult =
                                configuration.getIncoming().getResolutionResult();
                        return resolutionResult.getAllComponents().stream()
                                .map(ResolvedComponentResult::getId)
                                .filter(cid ->
                                        !cid.equals(resolutionResult.getRoot().getId()))
                                .filter(cid -> cid instanceof ModuleComponentIdentifier)
                                .map(mcid -> ((ModuleComponentIdentifier) mcid).getModuleIdentifier())
                                .map(mid -> configName + "|" + mid.getGroup() + ":" + mid.getName());
                    } catch (Exception e) {
                        throw new RuntimeException(
                                String.format(
                                        "Error during resolution of the dependency graph of configuration %s",
                                        configuration),
                                e);
                    }
                })
                .distinct()
                .sorted()
                .collect(Collectors.joining("\n"));

        try (BufferedWriter writer =
                Files.newBufferedWriter(getOutputFile().get().getAsFile().toPath())) {
            writer.write(content);
        } catch (IOException e) {
            throw new UncheckedIOException("Error writing resolved modules file", e);
        }
    }
}
