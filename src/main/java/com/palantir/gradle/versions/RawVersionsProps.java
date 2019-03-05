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

import static java.util.stream.Collectors.toSet;

import com.google.common.base.Preconditions;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.immutables.value.Value;

/**
 * Representation of a versions.props file used exclusively by {@link CheckMinimalVersionsTask}.
 * Supports '# linter:OFF'.
 */
@Value.Immutable
abstract class RawVersionsProps {
    private static final Pattern VERSION_FORCE_REGEX = Pattern.compile("([^:=\\s]+:[^:=\\s]+)\\s*=\\s*([^\\s]+)");

    /** Raw lines from file - may include comments. */
    abstract List<String> lines();

    abstract List<VersionForce> forces();

    @Value.Immutable
    public interface VersionForce {
        String name();
        String version();
        Integer lineNumber();

        static VersionForce of(String name, String version) {
            return ImmutableVersionForce.builder().name(name).version(version).build();
        }
    }

    static RawVersionsProps fromFile(File propsFile) throws IOException {
        Preconditions.checkArgument(propsFile.exists(), "File not found");
        try (Stream<String> lines = Files.lines(propsFile.toPath())) {
            return fromLines(lines);
        }
    }

    static RawVersionsProps fromLines(Stream<String> linesStream) {
        List<String> lines = linesStream.map(String::trim).collect(Collectors.toList());

        ImmutableRawVersionsProps.Builder builder = ImmutableRawVersionsProps.builder()
                .addAllLines(lines);

        boolean active = true;
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);

            // skip lines while linter:OFF
            if (line.equals("# linter:ON")) {
                active = true;
            } else if (line.equals("# linter:OFF")) {
                active = false;
            }
            if (!active) {
                continue;
            }

            // strip possibly trailing comments so VERSION_FORCE_REGEX doesn't have to match leading/trailing spaces
            int commentIndex = line.indexOf("#");
            String trimmedLine = (commentIndex >= 0 ? line.substring(0, commentIndex) : line).trim();

            Matcher matcher = VERSION_FORCE_REGEX.matcher(trimmedLine);
            if (matcher.matches()) {
                VersionForce force = ImmutableVersionForce.builder()
                        .name(matcher.group(1))
                        .version(matcher.group(2))
                        .lineNumber(index)
                        .build();
                builder.addForces(force);
            }
        }

        return builder.build();
    }

    void writebackSubsetOfLines(Set<VersionForce> linesToRemove, File propsFile) throws IOException {
        List<String> lines = lines();
        Set<Integer> indicesToSkip = linesToRemove.stream().map(VersionForce::lineNumber).collect(toSet());

        try (BufferedWriter writer0 = Files.newBufferedWriter(propsFile.toPath(), StandardOpenOption.TRUNCATE_EXISTING);
                PrintWriter writer = new PrintWriter(writer0)) {
            for (int index = 0; index < lines.size(); index++) {
                if (!indicesToSkip.contains(index)) {
                    writer.println(lines.get(index));
                }
            }
        }
    }
}

