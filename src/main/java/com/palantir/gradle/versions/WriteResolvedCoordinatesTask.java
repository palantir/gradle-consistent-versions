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
import java.util.Comparator;
import java.util.List;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

public abstract class WriteResolvedCoordinatesTask extends DefaultTask {

    private static final ObjectMapper OBJECT_MAPPER = new JsonMapper();

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    @Input
    public abstract SetProperty<ResolvedCoordinate> getResolvedCoordinates();

    /**
     * Files from dependent projects' writeResolvedCoordinatesTask.
     * This input establishes task ordering to prevent parallel resolution lock errors
     * when resolving configurations that have cross-project dependencies.
     * The actual file contents are not used; this is purely for ordering.
     */
    @InputFiles
    @PathSensitive(PathSensitivity.NONE)
    public abstract ConfigurableFileCollection getDependentProjectModule();

    @TaskAction
    public final void writeResolvedCoordinates() {
        List<ResolvedCoordinate> sorted = getResolvedCoordinates().get().stream()
                .sorted(Comparator.comparing(ResolvedCoordinate::group)
                        .thenComparing(ResolvedCoordinate::module)
                        .thenComparing(ResolvedCoordinate::configuration))
                .toList();
        try {
            OBJECT_MAPPER.writeValue(getOutputFile().get().getAsFile(), sorted);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write resolved module identifiers", e);
        }
    }
}
