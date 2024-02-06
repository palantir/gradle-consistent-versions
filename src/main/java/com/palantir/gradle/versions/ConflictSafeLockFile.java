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

import com.google.common.base.Preconditions;
import com.palantir.gradle.versions.lockstate.FullLockState;
import com.palantir.gradle.versions.lockstate.ImmutableLine;
import com.palantir.gradle.versions.lockstate.Line;
import com.palantir.gradle.versions.lockstate.LockState;
import com.palantir.gradle.versions.lockstate.LockStates;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.gradle.api.GradleException;

final class ConflictSafeLockFile {
    private static final String HEADER_COMMENT = "# Run ./gradlew writeVersionsLock to regenerate this file";
    private static final Pattern LINE_PATTERN =
            Pattern.compile("(?<group>[^(:]+):(?<artifact>[^(:]+):(?<version>[^(:\\s]+)"
                    + "\\s+\\((?<num>\\d+) constraints: (?<hash>\\w+)\\)");
    private static final String TEST_DEPENDENCIES_MARKER = "[Test dependencies]";

    private final Path lockfile;

    ConflictSafeLockFile(Path lockfile) {
        this.lockfile = lockfile;
    }

    /** Reads and returns the {@link LockState}. */
    public LockState readLocks() {
        try (Stream<String> linesStream = Files.lines(lockfile)) {
            List<String> lines =
                    linesStream.filter(line -> !line.trim().startsWith("#")).collect(Collectors.toList());
            int testDependenciesPosition = lines.indexOf(TEST_DEPENDENCIES_MARKER);
            Stream<String> productionDeps;
            Stream<String> testDeps;
            if (testDependenciesPosition >= 0) {
                productionDeps = lines
                        .subList(0, testDependenciesPosition - 1) // skip blank line before marker
                        .stream();
                testDeps = lines.subList(testDependenciesPosition + 1, lines.size()).stream();
            } else {
                productionDeps = lines.stream().filter(line -> !line.trim().startsWith("#"));
                testDeps = Stream.empty();
            }

            return LockState.from(parseLines(productionDeps), parseLines(testDeps));
        } catch (IOException e) {
            throw new GradleException(
                    String.format("Couldn't load versions from palantir dependency lock file: %s", lockfile), e);
        }
    }

    public Stream<Line> parseLines(Stream<String> stringStream) {
        return stringStream
                .map(line -> {
                    Matcher matcher = LINE_PATTERN.matcher(line);
                    Preconditions.checkState(
                            matcher.matches(),
                            "Found unparseable line in dependency lock file '%s': %s",
                            lockfile,
                            line);
                    return matcher;
                })
                .map(matcher -> ImmutableLine.of(
                        matcher.group("group"),
                        matcher.group("artifact"),
                        matcher.group("version"),
                        Integer.parseInt(matcher.group("num")),
                        matcher.group("hash")));
    }

    public void writeLocks(FullLockState fullLockState) {
        LockState lockState = LockStates.toLockState(fullLockState);
        try (BufferedWriter writer =
                Files.newBufferedWriter(lockfile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            writer.append(HEADER_COMMENT);
            writer.newLine();

            lockState.productionLinesByModuleIdentifier().values().forEach(line -> writeLine(line, writer));

            if (!lockState.testLinesByModuleIdentifier().isEmpty()) {
                writer.newLine();
                writer.write(TEST_DEPENDENCIES_MARKER);
                writer.newLine();
                lockState.testLinesByModuleIdentifier().values().forEach(line -> writeLine(line, writer));
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to write lock file: " + lockfile, e);
        }
    }

    private static void writeLine(Line line, BufferedWriter writer) {
        try {
            writer.append(line.stringRepresentation());
            writer.newLine();
        } catch (IOException e) {
            throw new RuntimeException("Failed writing line", e);
        }
    }
}
