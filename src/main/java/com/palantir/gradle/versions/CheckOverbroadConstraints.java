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
import com.google.common.base.Splitter;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.palantir.gradle.failurereports.exceptions.ExceptionWithSuggestion;
import com.palantir.gradle.versions.lockstate.Line;
import com.palantir.gradle.versions.lockstate.LockState;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import one.util.streamex.StreamEx;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;
import org.gradle.language.base.plugins.LifecycleBasePlugin;

public abstract class CheckOverbroadConstraints extends DefaultTask {

    @Input
    @Option(option = "fix", description = "Whether to apply the suggested fix to versions.props")
    public abstract Property<Boolean> getShouldFix();

    @InputFile
    public abstract RegularFileProperty getPropsFile();

    @InputFile
    public abstract RegularFileProperty getLockFile();

    public CheckOverbroadConstraints() {
        getShouldFix().set(false);
        setGroup(LifecycleBasePlugin.VERIFICATION_GROUP);
        setDescription(
                "Ensures matched versions in your versions.lock are pinned to avoid wasted dependency resolution.");
        getOutputs().upToDateWhen(_task -> false); // task has no outputs, this is needed for it to be up to date
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

        Map<String, List<String>> oldToNewLines = determineNewLines(versionsProps, lockState);
        List<String> newLines =
                oldToNewLines.values().stream().flatMap(Collection::stream).collect(Collectors.toList());

        // If an old line is in the newLines we are going to remove then re-add it so no need to add to the error
        // message
        newLines.removeIf(line -> oldToNewLines.keySet().stream().anyMatch(line::startsWith));

        if (newLines.isEmpty()) {
            return;
        }

        if (getShouldFix().get()) {
            getLogger().lifecycle("Adding pins to versions.props:\n" + String.join("\n", newLines));
            writeVersionsProps(getPropsFile().get().getAsFile(), oldToNewLines);
            return;
        }

        throw new ExceptionWithSuggestion(
                String.join(
                        "\n",
                        "Over-broad version constraints found in versions.props.",
                        "Over-broad constraints often arise due to wildcards in versions.props",
                        "which apply to more dependencies than they should, this can lead to slow builds.",
                        "The following additional pins are recommended:",
                        String.join("\n", newLines),
                        "",
                        "Run ./gradlew checkOverbroadConstraints --fix to add them.",
                        "See https://github.com/palantir/gradle-consistent-versions?tab=readme-ov-file#gradlew-checkoverbroadconstraints"
                            + " for details"),
                "./gradlew checkOverbroadConstraints --fix");
    }

    @VisibleForTesting
    static Map<String, List<String>> determineNewLines(VersionsProps versionsProps, LockState lockState) {
        // The general aim here is to match what a human would do to resolving the props. Generally the process is:
        // 1. Get all wildcard props and determine if their locks have more than one version.
        // 2. Generate new line that are as broad as possible while being unique - we add some style here by stopping
        //    after a word is complete and never suggesting adding a wildcard to a group
        // 3. Determine if there is a set of new line suggestions that are more common than the rest, if there is then
        //    that version is removed from the new lines and the original constraint is used to cover that version.
        // Looks at the output in src/test/resources/overbroadConstraintsTests to see example outputs

        Map<String, Line> lineByArtifact = lockState.allLines().stream()
                .collect(Collectors.toMap(line -> line.identifier().toString(), line -> line));

        Set<String> exactConstraints = versionsProps.getFuzzyResolver().exactMatches();
        Set<String> unmatchedArtifacts = new HashSet<>(Sets.difference(lineByArtifact.keySet(), exactConstraints));

        Map<String, List<String>> newLinesMap = new HashMap<>();

        // Globs are sorted by specificity
        versionsProps.getFuzzyResolver().globs().forEach(glob -> {
            // Find artifacts that match the current glob among unmatchedArtifacts
            Set<String> matchedArtifacts =
                    unmatchedArtifacts.stream().filter(glob::matches).collect(Collectors.toSet());

            if (!matchedArtifacts.isEmpty()) {
                List<String> newPins = computeNewLines(matchedArtifacts, lineByArtifact);
                newLinesMap.put(glob.getRawPattern(), newPins);

                // Remove the matched artifacts from unmatchedArtifacts to prevent further matching with less specific
                // globs
                unmatchedArtifacts.removeAll(matchedArtifacts);
            }
        });

        newLinesMap.replaceAll((key, lines) -> removeMostCommonPins(versionsProps, key, lines));

        return newLinesMap;
    }

    private static List<String> computeNewLines(Set<String> artifacts, Map<String, Line> lineByArtifact) {
        long uniqueVersionCount = artifacts.stream()
                .map(lineByArtifact::get)
                .map(Line::version)
                .filter(Objects::nonNull)
                .distinct()
                .count();

        if (uniqueVersionCount <= 1) {
            return Collections.emptyList();
        }

        return artifacts.stream()
                .map(lineByArtifact::get)
                .map(line -> makeUniqueWildcard(
                        line, artifacts.stream().map(lineByArtifact::get).collect(Collectors.toList())))
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    private static List<String> removeMostCommonPins(
            VersionsProps versionsProps, String originalPin, List<String> newPins) {
        Map<String, Long> versionCounts = newPins.stream()
                .map(line -> Iterables.get(Splitter.on('=').split(line), 1).trim())
                .filter(version -> !version.isEmpty())
                .collect(Collectors.groupingBy(version -> version, Collectors.counting()));

        if (versionCounts.isEmpty()) {
            return newPins;
        }

        long maxCount =
                versionCounts.values().stream().mapToLong(Long::longValue).max().orElse(0);
        long numMax = versionCounts.values().stream()
                .filter(count -> count == maxCount)
                .count();

        if (numMax > 1) {
            // There is no single most common version for pins
            return newPins;
        }

        Optional<String> mostCommonVersion = versionCounts.entrySet().stream()
                .filter(entry -> entry.getValue() == maxCount)
                .map(Map.Entry::getKey)
                .findFirst();

        if (mostCommonVersion.isEmpty()) {
            return newPins;
        }

        return StreamEx.of(newPins)
                .remove(line -> {
                    List<String> parts = Splitter.on('=')
                            .trimResults()
                            .omitEmptyStrings()
                            .limit(2)
                            .splitToList(line);
                    return parts.size() > 1 && parts.get(1).equals(mostCommonVersion.get());
                })
                .prepend(originalPin + " = "
                        + versionsProps.getFuzzyResolver().versions().get(originalPin))
                .toList();
    }

    public static String makeUniqueWildcard(Line input, List<Line> lines) {
        Set<String> linesWithVersionDifferentFromInput = lines.stream()
                .filter(line -> !line.version().equals(input.version()))
                .map(line -> line.identifier().toString())
                .collect(Collectors.toSet());

        String lineIdentifier = input.identifier().toString();

        String minimalLineIdentifier = IntStream.rangeClosed(1, lineIdentifier.length())
                .filter(i -> isAcceptablePrefixEnd(i, lineIdentifier))
                .mapToObj(i -> lineIdentifier.substring(0, i))
                .filter(prefix -> prefix.contains(":"))
                .filter(prefix -> linesWithVersionDifferentFromInput.stream().noneMatch(s -> s.startsWith(prefix)))
                .findFirst()
                .orElse(lineIdentifier);

        // cannot make wildcard and must pin at full length
        if (minimalLineIdentifier.equals(lineIdentifier)) {
            return String.format("%s = %s", input.identifier(), input.version());
        }

        // Can wildcard the package name
        return String.format("%s = %s", minimalLineIdentifier + "*", input.version());
    }

    private static boolean isAcceptablePrefixEnd(int index, String target) {
        if (index <= 0 || index > target.length()) {
            return false;
        }

        if (index == target.length()) {
            // Reached the end of the string
            return true;
        }

        if (target.charAt(index - 1) == ':') {
            return true;
        }

        return !Character.isLetterOrDigit(target.charAt(index));
    }

    private static void writeVersionsProps(File propsFile, Map<String, List<String>> oldToNewLines) {
        List<String> existingLines = readVersionsPropsLines(propsFile);

        List<String> updatedLines = generateUpdatedPropsLines(oldToNewLines, existingLines);

        String content = String.join(System.lineSeparator(), updatedLines);

        try {
            Files.writeString(propsFile.toPath(), content);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @VisibleForTesting
    static List<String> generateUpdatedPropsLines(Map<String, List<String>> oldToNewLines, List<String> existingLines) {
        List<String> updatedLines = new ArrayList<>();

        for (String line : existingLines) {
            updatedLines.add(line);
            // Iterate through the map to check for matching "old line" prefixes
            for (Map.Entry<String, List<String>> entry : oldToNewLines.entrySet()) {
                String oldLinePrefix = entry.getKey();

                if (line.startsWith(oldLinePrefix)) {
                    List<String> newLines = entry.getValue();
                    if (newLines != null && !newLines.isEmpty()) {
                        updatedLines.addAll(newLines);
                        updatedLines.remove(line);
                    }
                    break;
                }
            }
        }
        return updatedLines;
    }

    private static List<String> readVersionsPropsLines(File propsFile) {
        try {
            String content = Files.readString(propsFile.toPath());
            return Splitter.on("\n").splitToList(content);
        } catch (IOException e) {
            throw new RuntimeException("Error reading " + propsFile.toPath(), e);
        }
    }
}
