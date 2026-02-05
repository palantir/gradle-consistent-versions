/*
 * (c) Copyright 2018 Palantir Technologies Inc. All rights reserved.
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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import com.palantir.gradle.failurereports.exceptions.ExceptionWithSuggestion;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;
import org.gradle.language.base.plugins.LifecycleBasePlugin;

public abstract class CheckUnusedConstraintsTask extends DefaultTask {

    private static final TypeReference<Set<ResolvedModule>> SET_OF_RESOLVED_MODULES = new TypeReference<>() {};

    private static final ObjectMapper OBJECT_MAPPER = new JsonMapper();

    public CheckUnusedConstraintsTask() {
        getShouldFix().convention(false);
        setGroup(LifecycleBasePlugin.VERIFICATION_GROUP);
        setDescription("Ensures all versions in your versions.props correspond to an actual gradle dependency");
        getOutputs().upToDateWhen(_task -> true); // task has no outputs, this is needed for it to be up to date
    }

    @InputFiles
    @PathSensitive(PathSensitivity.NONE)
    public abstract ConfigurableFileCollection getResolvedModulesFiles();

    @Input
    public abstract SetProperty<String> getExcludeConfigurations();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getPropsFile();

    @Input
    @Option(option = "fix", description = "Whether to apply the suggested fix to versions.props")
    public abstract Property<Boolean> getShouldFix();

    @TaskAction
    public final void checkNoUnusedPin() {
        Set<String> excludedConfigs = getExcludeConfigurations().get();
        Set<String> artifacts = getResolvedModulesFiles().getFiles().stream()
                .map(CheckUnusedConstraintsTask::readModulesFile)
                .flatMap(Set::stream)
                .filter(module -> !excludedConfigs.contains(module.configuration()))
                .map(ResolvedModule::module)
                .collect(Collectors.toSet());

        VersionsProps versionsProps =
                VersionsProps.loadFromFile(getPropsFile().get().getAsFile().toPath());

        Set<String> exactConstraints = versionsProps.getFuzzyResolver().exactMatches();
        Set<String> unusedConstraints = new HashSet<>(Sets.difference(exactConstraints, artifacts));
        Set<String> unmatchedArtifacts = new HashSet<>(Sets.difference(artifacts, exactConstraints));

        // assumes globs are sorted by specificity
        for (FuzzyPatternResolver.Glob glob : versionsProps.getFuzzyResolver().globs()) {
            if (!unmatchedArtifacts.removeIf(glob::matches)) {
                unusedConstraints.add(glob.getRawPattern());
            }
        }

        if (unusedConstraints.isEmpty()) {
            return;
        } else if (getShouldFix().get()) {
            getLogger()
                    .lifecycle("Removing unused pins from versions.props:\n"
                            + unusedConstraints.stream()
                                    .map(name -> String.format(" - '%s'", name))
                                    .collect(Collectors.joining("\n")));
            writeVersionsProps(getPropsFile().get().getAsFile(), unusedConstraints);
            return;
        }

        throw new ExceptionWithSuggestion(
                "There are unused pins in your versions.props: \n" + unusedConstraints + "\n\n"
                        + "Run ./gradlew checkUnusedConstraints --fix to remove them.",
                "./gradlew checkUnusedConstraints --fix");
    }

    private static Set<ResolvedModule> readModulesFile(File file) {
        try {
            return OBJECT_MAPPER.readValue(file, SET_OF_RESOLVED_MODULES);
        } catch (IOException e) {
            throw new UncheckedIOException("Error reading " + file, e);
        }
    }

    private static void writeVersionsProps(File propsFile, Set<String> unusedConstraints) {
        List<String> lines = readVersionsPropsLines(propsFile);
        try (BufferedWriter writer0 =
                        Files.newBufferedWriter(propsFile.toPath(), StandardOpenOption.TRUNCATE_EXISTING);
                PrintWriter writer = new PrintWriter(writer0)) {
            for (String line : lines) {
                if (unusedConstraints.stream().noneMatch(line::startsWith)) {
                    writer.println(line);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Error opening or creating " + propsFile.toPath(), e);
        }
    }

    private static List<String> readVersionsPropsLines(File propsFile) {
        try (Stream<String> lines = Files.lines(propsFile.toPath())) {
            return lines.collect(ImmutableList.toImmutableList());
        } catch (IOException e) {
            throw new UncheckedIOException("Error reading " + propsFile.toPath(), e);
        }
    }
}
