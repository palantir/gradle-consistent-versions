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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class VersionsPropsTest {
    @TempDir
    Path tempDir;

    @Test
    void load_valid_versions_props() throws IOException {
        Path propsFile = tempDir.resolve("versions.props");
        Files.writeString(propsFile, "com.palantir.test:test = 1.0.0");

        VersionsProps versionsProps = VersionsProps.loadFromFile(propsFile);
        assertThat(versionsProps.getFuzzyResolver().exactMatches()).containsExactly("com.palantir.test:test");
        assertThat(versionsProps.getFuzzyResolver().globs()).isEmpty();
    }

    @Test
    void fails_to_load_illegal_artifact() throws IOException {
        Path propsFile = tempDir.resolve("versions.props");
        Files.writeString(propsFile, "com.palantir.test:test:1.0.0");

        assertThatThrownBy(() -> VersionsProps.loadFromFile(propsFile))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid constraint");
    }

    @Test
    void fails_to_load_illegal_version() throws IOException {
        Path propsFile = tempDir.resolve("versions.props");
        Files.writeString(propsFile, "com.palantir.test:test = ");

        assertThatThrownBy(() -> VersionsProps.loadFromFile(propsFile))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid constraint");
    }
}
