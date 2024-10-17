/*
 * (c) Copyright 2023 Palantir Technologies Inc. All rights reserved.
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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import com.palantir.gradle.versions.FuzzyPatternResolver.Glob;
import com.palantir.gradle.versions.lockstate.Line;
import com.palantir.gradle.versions.lockstate.LockState;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFile;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;
import org.gradle.language.base.plugins.LifecycleBasePlugin;

public abstract class CheckOverbroadConstraints extends DefaultTask {

    @Internal
    protected abstract Property<Boolean> getShouldFixProperty();

    @Internal
    protected abstract RegularFileProperty getPropsFileProperty();

    @Internal
    protected abstract RegularFileProperty getLockFileProperty();

    public CheckOverbroadConstraints() {
        getShouldFixProperty().set(false);
        setGroup(LifecycleBasePlugin.VERIFICATION_GROUP);
        setDescription(
                "Ensures matched versions in your versions.lock are pinned to avoid wasted dependency resolution.");
        getOutputs().upToDateWhen(_task -> true); // task has no outputs, this is needed for it to be up to date
    }

    @InputFile
    public final Property<RegularFile> getLockFile() {
        return getLockFileProperty();
    }

    @InputFile
    public final Property<RegularFile> getPropsFile() {
        return getPropsFileProperty();
    }

    @Input
    public final Property<Boolean> getShouldFix() {
        return getShouldFixProperty();
    }

    @Option(option = "fix", description = "Whether to apply the suggested fix to versions.props")
    public final void setShouldFix(boolean shouldFix) {
        this.getShouldFixProperty().set(shouldFix);
    }

    @TaskAction
    public final void checkOverbroadConstraints() {
        VersionsProps versionsProps =
                VersionsProps.loadFromFile(getPropsFile().get().getAsFile().toPath());
        LockState lockState =
                new ConflictSafeLockFile(getLockFile().get().getAsFile().toPath()).readLocks();

        checkOverbroadConstraints(versionsProps, lockState);
    }

    private void checkOverbroadConstraints(VersionsProps versionsProps, LockState lockState) {

        List<String> newLines = determineNewLines(versionsProps, lockState);

        if (newLines.isEmpty()) {
            return;
        } else if (getShouldFixProperty().get()) {
            getLogger()
                    .lifecycle("Adding pins to versions.props:\n"
                            + newLines.stream().collect(Collectors.joining("\n")));
            writeVersionsProps(getPropsFile().get().getAsFile(), newLines);
            return;
        }

        throw new RuntimeException("There are efficient pins missing from your versions.props: \n"
                + newLines
                + "\n\n"
                + "Run ./gradlew checkOverbroadConstraints --fix to add them.");
    }

    @VisibleForTesting
    static List<String> determineNewLines(VersionsProps versionsProps, LockState lockState) {
        Map<String, Line> lineByArtifact = lockState.allLines().stream()
                .collect(Collectors.toMap(line -> line.identifier().toString(), line -> line));
        Set<String> artifacts = lineByArtifact.keySet();

        Set<String> exactConstraints = versionsProps.getFuzzyResolver().exactMatches();
        Set<String> unmatchedArtifacts = new HashSet<>(Sets.difference(artifacts, exactConstraints));

        // assumes globs are sorted by specificity
        return versionsProps.getFuzzyResolver().globs().stream()
                .flatMap(glob -> {
                    Set<String> matchedByGlob =
                            unmatchedArtifacts.stream().filter(glob::matches).collect(Collectors.toSet());
                    unmatchedArtifacts.removeAll(matchedByGlob);
                    return computeNewLines(getVersionPin(versionsProps, glob), matchedByGlob, lineByArtifact).stream();
                })
                .collect(Collectors.toList());
    }

    private static String getVersionPin(VersionsProps versionsProps, Glob glob) {
        return versionsProps.getFuzzyResolver().versions().get(glob.getRawPattern());
    }

    private static List<String> computeNewLines(
            String pinnedVersion, Set<String> artifacts, Map<String, Line> lineByArtifact) {
        return artifacts.stream()
                .map(lineByArtifact::get)
                // Remove if the version is the pinnedVersion.
                .filter(line -> !line.version().equals(pinnedVersion))
                .map(line -> String.format("%s = %s", line.identifier(), line.version()))
                .collect(Collectors.toList());
    }

    private static void writeVersionsProps(File propsFile, List<String> newLines) {
        List<String> lines = readVersionsPropsLines(propsFile);
        String content = Stream.of(lines, newLines).flatMap(Collection::stream).collect(Collectors.joining("\n"));
        try {
            Files.writeString(propsFile.toPath(), content);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static List<String> readVersionsPropsLines(File propsFile) {
        try (Stream<String> lines = Files.lines(propsFile.toPath())) {
            return lines.collect(ImmutableList.toImmutableList());
        } catch (IOException e) {
            throw new RuntimeException("Error reading " + propsFile.toPath());
        }
    }
}
