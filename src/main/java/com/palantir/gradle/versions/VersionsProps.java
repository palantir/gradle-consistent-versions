/*
 * (c) Copyright 2019 Palantir Technologies Inc. All rights reserved.
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

import com.google.common.base.CharMatcher;
import com.google.common.base.Preconditions;
import com.google.common.collect.Sets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.gradle.api.artifacts.DependencyConstraint;
import org.gradle.api.artifacts.ModuleIdentifier;
import org.gradle.api.artifacts.dsl.DependencyConstraintHandler;

/** A {@code versions.props} file. */
public final class VersionsProps {
    private static final Pattern CONSTRAINT = Pattern.compile("^([^# :]+:[^# ]+)\\s*=\\s*([^# ]+)#?.*$");

    private final FuzzyPatternResolver fuzzyResolver;
    private final Map<String, String> patternToPlatform;

    private VersionsProps(FuzzyPatternResolver fuzzyResolver) {
        this.fuzzyResolver = fuzzyResolver;
        this.patternToPlatform =
                Sets.difference(fuzzyResolver.versions().keySet(), fuzzyResolver.exactMatches()).stream()
                        .collect(Collectors.toMap(key -> key, this::constructPlatform));
    }

    public FuzzyPatternResolver getFuzzyResolver() {
        return fuzzyResolver;
    }

    public static VersionsProps loadFromFile(Path path) {
        FuzzyPatternResolver.Builder builder = FuzzyPatternResolver.builder();
        Map<String, String> versions = new HashMap<>();
        for (String line : safeReadLines(path)) {
            Matcher constraint = CONSTRAINT.matcher(line);
            if (constraint.matches()) {
                String key = constraint.group(1);
                String value = constraint.group(2);
                Preconditions.checkArgument(
                        CharMatcher.is(':').countIn(key) == 1, "Encountered invalid artifact name '%s'", key);
                Preconditions.checkArgument(!value.isEmpty(), "Encountered missing version for artifact '%s'", value);
                if (versions.containsKey(key)) {
                    throw new RuntimeException(
                            "Encountered duplicate constraint '" + key + "'. Please remove one of the entries");
                }
                versions.put(key, value);
            } else if (!line.trim().isEmpty() && !line.startsWith("#")) {
                throw new IllegalArgumentException("Encountered invalid constraint " + line);
            }
        }
        builder.putAllVersions(versions);
        return new VersionsProps(builder.build());
    }

    private static List<String> safeReadLines(Path file) {
        try {
            return Files.readAllLines(file);
        } catch (IOException e) {
            throw new RuntimeException("Error reading " + file);
        }
    }

    /** Construct a trivial {@link VersionsProps} that has no version recommendations. */
    static VersionsProps empty() {
        return new VersionsProps(FuzzyPatternResolver.builder().build());
    }

    public Stream<DependencyConstraint> constructConstraints(DependencyConstraintHandler handler) {
        Map<String, String> versions = fuzzyResolver.versions();
        return Stream.concat(
                fuzzyResolver.exactMatches().stream()
                        .map(key -> key + ":" + versions.get(key))
                        .map(handler::create),
                patternToPlatform.entrySet().stream()
                        .map(entry -> entry.getValue() + ":" + versions.get(entry.getKey()))
                        .map(handler::create));
    }

    /**
     * Get a recommended version for a module if it matches one of the non-exact platforms. This is necessary for direct
     * dependency injection, which is not supported by virtual platforms. See <a
     * href=https://github.com/gradle/gradle/issues/7954>gradle#7954</a> for more details.
     */
    public Optional<String> getStarVersion(ModuleIdentifier dependency) {
        String notation = dependency.getGroup() + ":" + dependency.getName();
        return fuzzyResolver.patternFor(notation).map(fuzzyResolver.versions()::get);
    }

    /**
     * Returns the most specific platform for the given dependency, unless the dependency has a more specific exact
     * version defined, in which case return {@link Optional#empty}.
     */
    public Optional<String> getPlatform(ModuleIdentifier dependency) {
        String notation = dependency.getGroup() + ":" + dependency.getName();
        if (fuzzyResolver.exactMatches().contains(notation)) {
            return Optional.empty();
        }
        return fuzzyResolver.patternFor(notation).map(patternToPlatform::get);
    }

    private String constructPlatform(String glob) {
        int occurrences = -1;
        String sub = glob;
        int lastIdx = 0;
        while (lastIdx != -1) {
            lastIdx = sub.indexOf(":");
            sub = sub.substring(lastIdx + 1);
            occurrences++;
        }
        String sanitized = glob.replaceAll("\\*", "_");
        if (occurrences == 0) {
            return "org:" + sanitized;
        }
        if (occurrences >= 2) {
            throw new IllegalArgumentException("Encountered a glob constraint with more than one ':' in it: " + glob);
        }
        return sanitized;
    }
}
