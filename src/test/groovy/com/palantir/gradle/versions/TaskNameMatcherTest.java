/*
 * (c) Copyright 2021 Palantir Technologies Inc. All rights reserved.
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

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TaskNameMatcherTest {
    private final TaskNameMatcher taskNameMatcher = new TaskNameMatcher("writeVersionsLocks");

    @ParameterizedTest
    @ValueSource(
            strings = {
                "writeVersionsLocks",
                "writeVersionsLock",
                "writeVersionLocks",
                "wVL",
                "wVersionL",
                "writeVersionsL",
                "wrVerLoc"
            })
    void matches(String taskName) {
        assertThat(taskNameMatcher.matches(taskName)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"foo", "write", "writeVersion", "writeVersions", "writeVersionsFoobar", "", "W", "WVL"})
    void does_not_match(String taskName) {
        assertThat(taskNameMatcher.matches(taskName)).isFalse();
    }
}
