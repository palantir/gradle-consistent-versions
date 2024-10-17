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
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class CheckOverbroadConstraintsTest {

    private static VersionsProps createVersionProps(String contents) {
        return VersionsProps.fromLines(List.of(contents.split("\n")));
    }

    private static LockState createLockState(String productionContents) {
        ConflictSafeLockFile lockReader = new ConflictSafeLockFile(null);
        return LockState.from(
                lockReader.parseLines(Stream.of(productionContents.split("\n"))),
                lockReader.parseLines(Stream.empty()));
    }

    @Test
    void test_no_missing_pins_returns_empty() {
        VersionsProps versionsProps = createVersionProps("com.example.*:*= 1.0.0\ncom.example.core:module = 1.0.1");
        LockState lockState = createLockState("com.example.core:module:1.0.1 (2 constraints: abcdef1)\n"
                + "com.example.someartifact:artifact:1.0.0 (2 constraints: abcdef1)");

        List<String> newLines = CheckOverbroadConstraints.determineNewLines(versionsProps, lockState);

        assertThat(newLines).isEmpty();
    }

    @Test
    void test_versions_props_missing_pins_are_generated() {
        VersionsProps versionsProps = createVersionProps("com.example.*:*= 1.0.0");
        LockState lockState = createLockState("com.example.core:module:1.0.1 (2 constraints: abcdef1)\n"
                + "com.example.someartifact:artifact:1.0.0 (2 constraints: abcdef1)");

        List<String> newLines = CheckOverbroadConstraints.determineNewLines(versionsProps, lockState);

        assertThat(newLines).containsExactly("com.example.core:module = 1.0.1");
    }
}
