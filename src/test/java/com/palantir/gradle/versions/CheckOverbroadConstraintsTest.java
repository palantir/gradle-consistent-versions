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
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class CheckOverbroadConstraintsTest {

    private static VersionsProps createVersionProps(String contents) {
        return VersionsProps.fromLines(List.of(contents.split("\n")), null);
    }

    private static LockState createLockState(String productionContents) {
        ConflictSafeLockFile lockReader = new ConflictSafeLockFile(null);
        return LockState.from(
                lockReader.parseLines(Stream.of(productionContents.split("\n"))),
                lockReader.parseLines(Stream.empty()));
    }

    @Test
    void test_no_missing_pins_returns_empty() {
        VersionsProps versionsProps =
                createVersionProps(String.join("\n", "com.example.*:*= 1.0.0", "com.example.core:module = 1.0.1"));
        LockState lockState = createLockState(String.join(
                "\n",
                "com.example.core:module:1.0.1 (2 constraints: abcdef1)",
                "com.example.someArtifact:artifact:1.0.0 (2 constraints: abcdef1)"));

        Map<String, List<String>> oldToNewLines = CheckOverbroadConstraints.determineNewLines(versionsProps, lockState);
        List<String> newLines =
                oldToNewLines.values().stream().flatMap(Collection::stream).collect(Collectors.toList());

        assertThat(newLines).isEmpty();
    }

    @Test
    void test_no_missing_pins_no_wildcards_returns_empty() {
        VersionsProps versionsProps = createVersionProps(
                String.join("\n", "com.example.someArtifact:artifact= 1.0.0", "com.example.core:module = 1.0.1"));
        LockState lockState = createLockState(String.join(
                "\n",
                "com.example.core:module:1.0.1 (2 constraints: abcdef1)",
                "com.example.someArtifact:artifact:1.0.0 (2 constraints: abcdef1)"));

        Map<String, List<String>> oldToNewLines = CheckOverbroadConstraints.determineNewLines(versionsProps, lockState);
        List<String> newLines =
                oldToNewLines.values().stream().flatMap(Collection::stream).collect(Collectors.toList());

        assertThat(newLines).isEmpty();
    }

    @Test
    void test_all_above_pin_returns_empty() {
        VersionsProps versionsProps = createVersionProps("com.example.*:*= 1.0.0");
        LockState lockState = createLockState(String.join(
                "\n",
                "com.example.core:module:1.0.1 (2 constraints: abcdef1)",
                "com.example.someArtifact:artifact:1.0.1 (2 constraints: abcdef1)"));

        Map<String, List<String>> oldToNewLines = CheckOverbroadConstraints.determineNewLines(versionsProps, lockState);
        List<String> newLines =
                oldToNewLines.values().stream().flatMap(Collection::stream).collect(Collectors.toList());

        assertThat(newLines).isEmpty();
    }

    @Test
    void test_all_below_pin_returns_empty() {
        VersionsProps versionsProps = createVersionProps("com.example.*:*= 1.0.0");
        LockState lockState = createLockState(String.join(
                "\n",
                "com.example.core:module:0.0.1 (2 constraints: abcdef1)",
                "com.example.someArtifact:artifact:0.0.1 (2 constraints: abcdef1)"));

        Map<String, List<String>> oldToNewLines = CheckOverbroadConstraints.determineNewLines(versionsProps, lockState);
        List<String> newLines =
                oldToNewLines.values().stream().flatMap(Collection::stream).collect(Collectors.toList());

        assertThat(newLines).isEmpty();
    }

    @Test
    void test_multiple_pins_all_same_returns_empty() {
        VersionsProps versionsProps =
                createVersionProps(String.join("\n", "com.example.*:*= 1.0.0", "org.different.*:* = 2.0.0"));
        LockState lockState = createLockState(String.join(
                "\n",
                "com.example.core:module:1.0.1 (2 constraints: abcdef1)",
                "com.example.someArtifact:artifact:1.0.1 (2 constraints: abcdef1)",
                "org.different:differentArtifact:2.0.1 (2 constraints: abcdef1)",
                "org.different.someDifferentArtifact:artifact:2.0.1 (2 constraints: abcdef1)"));

        Map<String, List<String>> oldToNewLines = CheckOverbroadConstraints.determineNewLines(versionsProps, lockState);
        List<String> newLines =
                oldToNewLines.values().stream().flatMap(Collection::stream).collect(Collectors.toList());

        assertThat(newLines).isEmpty();
    }

    @Test
    void test_versions_props_missing_pins_are_generated() {
        VersionsProps versionsProps = createVersionProps("com.example.*:*= 1.0.0");
        LockState lockState = createLockState(String.join(
                "\n",
                "com.example.core:module:1.0.1 (2 constraints: abcdef1)",
                "com.example.someArtifact:artifact:1.0.0 (2 constraints: abcdef1)"));

        Map<String, List<String>> oldToNewLines = CheckOverbroadConstraints.determineNewLines(versionsProps, lockState);
        List<String> newLines =
                oldToNewLines.values().stream().flatMap(Collection::stream).collect(Collectors.toList());

        assertThat(newLines).containsExactly("com.example.core:* = 1.0.1");
    }

    @Test
    void test_suggests_star_if_possible() {
        VersionsProps versionsProps = createVersionProps("org.junit.*:* = 5.10.2");
        LockState lockState = createLockState(String.join(
                "\n",
                "org.junit.jupiter:junit-jupiter:5.10.2 (2 constraints: abcdef1)",
                "org.junit.platform:junit-platform-commons:1.10.2 (2 constraints: abcdef1)"));

        Map<String, List<String>> oldToNewLines = CheckOverbroadConstraints.determineNewLines(versionsProps, lockState);
        List<String> newLines =
                oldToNewLines.values().stream().flatMap(Collection::stream).collect(Collectors.toList());

        assertThat(newLines).containsExactly("org.junit.platform:* = 1.10.2");
    }

    @Test
    void test_suggests_star_in_complex_situations() {
        VersionsProps versionsProps = createVersionProps("org.junit.*:* = 5.10.2");
        LockState lockState = createLockState(String.join(
                "\n",
                "org.junit.jupiter:junit-jupiter:5.10.2 (2 constraints: abcdef1)",
                "org.junit.platform:junit-platform-commons:1.10.2 (2 constraints: abcdef1)",
                "org.junit.platform:junit-platform-new:1.10.2 (2 constraints: abcdef1)",
                "org.junit.different:so-very-different:1.10.2 (2 constraints: abcdef1)",
                "org.junit.platform:junit-different:1.09.1 (2 constraints: abcdef1)",
                "org.junit.platform:junit-different-again:1.08.1 (2 constraints: abcdef1)"));

        Map<String, List<String>> oldToNewLines = CheckOverbroadConstraints.determineNewLines(versionsProps, lockState);
        List<String> newLines =
                oldToNewLines.values().stream().flatMap(Collection::stream).collect(Collectors.toList());

        assertThat(newLines)
                .containsExactlyInAnyOrder(
                        "org.junit.platform:junit-platform-* = 1.10.2",
                        "org.junit.platform:junit-different = 1.09.1",
                        "org.junit.platform:junit-different-* = 1.08.1",
                        "org.junit.different:* = 1.10.2");
    }

    @Test
    void test_suggest_double_star() {
        VersionsProps versionsProps = createVersionProps("org.*:* = 1.0.0");
        LockState lockState = createLockState(String.join(
                "\n",
                "org.example.core:module:1.0.0 (2 constraints: abcdef1)",
                "org.different.example:artifact:2.0.0 (2 constraints: abcdef1)"));

        Map<String, List<String>> oldToNewLines = CheckOverbroadConstraints.determineNewLines(versionsProps, lockState);
        List<String> newLines =
                oldToNewLines.values().stream().flatMap(Collection::stream).collect(Collectors.toList());

        assertThat(newLines).containsExactly("org.different.*:* = 2.0.0");
    }
}
