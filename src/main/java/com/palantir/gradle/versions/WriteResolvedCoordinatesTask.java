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
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Set;
import org.gradle.api.DefaultTask;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.result.ResolvedComponentResult;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

public abstract class WriteResolvedCoordinatesTask extends DefaultTask {

    private static final ObjectMapper OBJECT_MAPPER = new JsonMapper();

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    /** Resolving this establishes cross-project task ordering for Gradle 9 --parallel. */
    @Input
    public abstract SetProperty<ResolvedComponentResult> getOrderingResult();

    @Input
    public abstract MapProperty<String, Set<ResolvedComponentResult>> getConfigurationNameToRootComponents();

    @TaskAction
    public final void writeResolvedCoordinates() {
        List<ResolvedCoordinate> sorted = getConfigurationNameToRootComponents().get().entrySet().stream()
                .flatMap(entry -> entry.getValue().stream()
                        .map(ResolvedComponentResult::getId)
                        .filter(ModuleComponentIdentifier.class::isInstance)
                        .map(ModuleComponentIdentifier.class::cast)
                        .map(mcid -> ResolvedCoordinate.of(entry.getKey(), mcid.getGroup(), mcid.getModule())))
                .sorted()
                .distinct()
                .toList();
        try {
            OBJECT_MAPPER.writeValue(getOutputFile().get().getAsFile(), sorted);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write resolved module identifiers", e);
        }
    }
}
