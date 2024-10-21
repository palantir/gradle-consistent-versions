/*
 * (c) Copyright 2024 Palantir Technologies Inc. All rights reserved.
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

import static org.assertj.core.api.Assertions.assertThat;

import com.palantir.gradle.versions.lockstate.LockState;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.assertj.core.util.diff.DiffUtils;
import org.assertj.core.util.diff.Patch;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class CheckOverbroadConstraintsTest {

    private static final String basePath = "src/test/resources/overbroadConstraintsTest/";
    private static final boolean IN_CI =
            Optional.ofNullable(System.getenv("CI")).equals(Optional.of("true"));

    @BeforeAll
    static void beforeAll() throws IOException {
        if (!IN_CI) {
            deleteOldTests(Paths.get(basePath));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("provideTestCases")
    void newLinesCorrect(TestCase testCase) throws IOException {
        if (IN_CI) {
            checkTestCaseInResources(testCase);
        }
        testCase.writeToFile();
    }

    private static Stream<Arguments> provideTestCases() {
        Stream<TestCase> testCases = Stream.of(
                TestCase.builder("no_missing_pins_returns_empty.diff")
                        .withVersionsProps("com.example.*:*= 1.0.0", "com.example.core:module = 1.0.1")
                        .withVersionsLock(
                                "com.example.core:module:1.0.1 (2 constraints: abcdef1)",
                                "com.example.someArtifact:artifact:1.0.0 (2 constraints: abcdef1)")
                        .build(),
                TestCase.builder("no_missing_pins_no_wildcards_returns_empty.diff")
                        .withVersionsProps(
                                "com.example.someArtifact:artifact= 1.0.0", "com.example.core:module = 1.0.1")
                        .withVersionsLock(
                                "com.example.core:module:1.0.1 (2 constraints: abcdef1)",
                                "com.example.someArtifact:artifact:1.0.0 (2 constraints: abcdef1)")
                        .build(),
                TestCase.builder("all_above_pin_returns_empty.diff")
                        .withVersionsProps("com.example.*:*= 1.0.0")
                        .withVersionsLock(
                                "com.example.core:module:1.0.1 (2 constraints: abcdef1)",
                                "com.example.someArtifact:artifact:1.0.1 (2 constraints: abcdef1)")
                        .build(),
                TestCase.builder("all_below_pin_returns_empty.diff")
                        .withVersionsProps("com.example.*:*= 1.0.0")
                        .withVersionsLock(
                                "com.example.core:module:0.0.1 (2 constraints: abcdef1)",
                                "com.example.someArtifact:artifact:0.0.1 (2 constraints: abcdef1)")
                        .build(),
                TestCase.builder("multiple_pins_all_same_returns_empty.diff")
                        .withVersionsProps("com.example.*:*= 1.0.0", "org.different.*:* = 2.0.0")
                        .withVersionsLock(
                                "com.example.core:module:1.0.1 (2 constraints: abcdef1)",
                                "com.example.someArtifact:artifact:1.0.1 (2 constraints: abcdef1)",
                                "org.different:differentArtifact:2.0.1 (2 constraints: abcdef1)",
                                "org.different.someDifferentArtifact:artifact:2.0.1 (2 constraints: abcdef1)")
                        .build(),
                TestCase.builder("versions_props_missing_pins_are_generated.diff")
                        .withVersionsProps("com.example.*:*= 1.0.0")
                        .withVersionsLock(
                                "com.example.core:module:1.0.1 (2 constraints: abcdef1)",
                                "com.example.someArtifact:artifact:1.0.0 (2 constraints: abcdef1)")
                        .build(),
                TestCase.builder("suggests_star_if_possible.diff")
                        .withVersionsProps("com.example.*:*= 1.0.0")
                        .withVersionsLock(
                                "com.example.moduleA:artifact-new:1.0.0 (2 constraints: abcdef1)",
                                "com.example.moduleB:artifact-core:3.0.0 (2 constraints: abcdef1)")
                        .build(),
                TestCase.builder("suggests_star_in_complex_situations.diff")
                        .withVersionsProps("com.example.*:*= 1.0.0", "org.different.*:* = 2.0.0")
                        .withVersionsLock(
                                "com.example.core:artifact-random:1.0.0 (2 constraints: abcdef1)",
                                "com.example.module:artifact-platform-commons:2.0.0 (2 constraints: abcdef1)",
                                "com.example.module:artifact-platform-new:2.0.0 (2 constraints: abcdef1)",
                                "com.example.different:so-very-different:2.0.0 (2 constraints: abcdef1)",
                                "com.example.module:artifact-different:3.0.0 (2 constraints: abcdef1)",
                                "com.example.module:artifact-different-again:4.0.0 (2 constraints: abcdef1)",
                                "org.different.unimportant:random:2.0.0 (2 constraints: abcdef1)")
                        .build(),
                TestCase.builder("never_suggest_double_star.diff")
                        .withVersionsProps("com.*:* = 1.0.0")
                        .withVersionsLock(
                                "com.example.core:module:1.0.0 (2 constraints: abcdef1)",
                                "com.different.example:artifact:2.0.0 (2 constraints: abcdef1)")
                        .build());

        return testCases.map(Arguments::of);
    }

    private void checkTestCaseInResources(TestCase testCase) throws IOException {
        File baseDir = new File(basePath);
        File[] allFiles = baseDir.listFiles();

        if (allFiles == null) {
            throw new RuntimeException("Base directory does not exist or is not a directory.");
        }

        Set<String> baseDirFileNames =
                Arrays.stream(allFiles).map(File::getName).collect(Collectors.toSet());

        assertThat(baseDirFileNames.contains(testCase.fileName))
                .as("%s not in resource directory. Run tests locally to fix.", testCase.fileName)
                .isTrue();

        File resourceFile = Arrays.stream(allFiles)
                .filter(file -> file.getName().equals(testCase.fileName))
                .findFirst()
                .orElseThrow(() -> new RuntimeException(
                        String.format("%s found in file names but could not retrieve the file.", testCase.fileName)));

        String actualContent =
                Files.readString(resourceFile.toPath(), StandardCharsets.UTF_8).trim();

        assertThat(actualContent)
                .as(
                        "Contents of file '%s' do not match the expected test case. Run tests locally to fix.",
                        testCase.fileName)
                .isEqualTo(testCase.fileContents().trim());
    }

    private static void deleteOldTests(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    deleteOldTests(entry);
                    Files.delete(entry);
                } else {
                    Files.delete(entry);
                }
            }
        }
    }

    public static final class TestCase {
        private final String fileName;
        private final VersionsProps versionsProps;
        private final LockState lockState;
        private final String propsLines;
        private final String lockLines;

        private TestCase(Builder builder) {
            this.fileName = builder.fileName;
            this.versionsProps = builder.versionsProps;
            this.lockState = builder.lockState;
            this.propsLines = builder.propsLines;
            this.lockLines = builder.lockLines;
        }

        public static Builder builder(String testName) {
            return new Builder(testName);
        }

        public List<String> newPropsLines() {
            Map<String, List<String>> oldToNewLines =
                    CheckOverbroadConstraints.determineNewLines(versionsProps, lockState);
            return CheckOverbroadConstraints.generateUpdatedPropsLines(
                    oldToNewLines, Collections.singletonList(propsLines));
        }

        public String fileContents() {
            List<String> header = List.of("versions.lock:", lockLines, "", "versions.props diff:");
            List<String> original = new ArrayList<>(header);
            List<String> revised = new ArrayList<>(header);
            original.add(propsLines);
            revised.addAll(newPropsLines());

            Patch<String> patch = DiffUtils.diff(original, revised);

            List<String> diff = DiffUtils.generateUnifiedDiff(fileName, fileName, original, patch, header.size());
            if (diff.isEmpty()) {
                return String.join("\n", String.join("\n", header), "No difference");
            } else {
                return String.join("\n", diff);
            }
        }

        public void writeToFile() {
            try (BufferedWriter writer = Files.newBufferedWriter(getOutputFilePath(), StandardCharsets.UTF_8)) {
                writer.write(fileContents());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        private Path getOutputFilePath() throws IOException {
            Path outputPath = Paths.get(basePath + fileName);
            Files.createDirectories(outputPath.getParent());
            return outputPath;
        }

        @Override
        public String toString() {
            return fileName;
        }

        // Builder class
        public static final class Builder {
            private final String fileName;
            private VersionsProps versionsProps = VersionsProps.empty();
            private LockState lockState = null;
            private String propsLines = "";
            private String lockLines = "";

            private Builder(String fileName) {
                this.fileName = fileName;
            }

            public Builder withVersionsProps(String... lines) {
                this.propsLines = String.join("\n", lines);
                this.versionsProps = VersionsProps.fromLines(Arrays.asList(lines), null);
                return this;
            }

            public Builder withVersionsLock(String... lines) {
                this.lockLines = Arrays.stream(lines).sorted().collect(Collectors.joining("\n"));
                ConflictSafeLockFile lockReader = new ConflictSafeLockFile(null);
                this.lockState = LockState.from(
                        lockReader.parseLines(Arrays.stream(lines)), lockReader.parseLines(Stream.empty()));
                return this;
            }

            public TestCase build() {
                return new TestCase(this);
            }
        }
    }
}
