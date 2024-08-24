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

import com.palantir.gradle.failurereports.exceptions.ExceptionWithSuggestion;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
        Files.writeString(propsFile, "com.palantir.test:test = 1.0.0", StandardCharsets.UTF_8);

        VersionsProps versionsProps = VersionsProps.loadFromFile(propsFile);
        assertThat(versionsProps.getFuzzyResolver().exactMatches()).containsExactly("com.palantir.test:test");
        assertThat(versionsProps.getFuzzyResolver().globs()).isEmpty();
    }

    @Test
    void fails_to_load_illegal_artifact() throws IOException {
        Path propsFile = tempDir.resolve("versions.props");
        Files.writeString(propsFile, "com.palantir.test:test:1.0.0", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> VersionsProps.loadFromFile(propsFile))
                .isInstanceOf(ExceptionWithSuggestion.class)
                .hasMessageContaining("invalid constraint");
    }

    @Test
    void fails_to_load_illegal_version() throws IOException {
        Path propsFile = tempDir.resolve("versions.props");
        Files.writeString(propsFile, "com.palantir.test:test = ", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> VersionsProps.loadFromFile(propsFile))
                .isInstanceOf(ExceptionWithSuggestion.class)
                .hasMessageContaining("invalid constraint");
    }

    @Test
    void ignores_comment_on_its_own_line() throws IOException {
        Path propsFile = tempDir.resolve("versions.props");
        Files.writeString(
                propsFile, "# comment on its own line\ncom.palantir.test:test = 1.0.0", StandardCharsets.UTF_8);

        VersionsProps versionsProps = VersionsProps.loadFromFile(propsFile);
        assertThat(versionsProps.getFuzzyResolver().exactMatches()).containsExactly("com.palantir.test:test");
        assertThat(versionsProps.getFuzzyResolver().globs()).isEmpty();
    }

    @Test
    void ignores_comment_on_same_line() throws IOException {
        Path propsFile = tempDir.resolve("versions.props");
        Files.writeString(propsFile, "com.palantir.test:test = 1.0.0  # comment on same line", StandardCharsets.UTF_8);

        VersionsProps versionsProps = VersionsProps.loadFromFile(propsFile);
        assertThat(versionsProps.getFuzzyResolver().exactMatches()).containsExactly("com.palantir.test:test");
        assertThat(versionsProps.getFuzzyResolver().globs()).isEmpty();
    }

    @Test
    void ignores_comment_on_same_line_with_no_hash() throws IOException {
        Path propsFile = tempDir.resolve("versions.props");
        Files.writeString(
                propsFile, "com.palantir.test:test = 1.0.0  comment on same line with no hash", StandardCharsets.UTF_8);

        VersionsProps versionsProps = VersionsProps.loadFromFile(propsFile);
        assertThat(versionsProps.getFuzzyResolver().exactMatches()).containsExactly("com.palantir.test:test");
        assertThat(versionsProps.getFuzzyResolver().globs()).isEmpty();
    }

    @Test
    void ignores_comment_on_same_line_with_hash_and_no_spaces() throws IOException {
        Path propsFile = tempDir.resolve("versions.props");
        Files.writeString(
                propsFile,
                "com.palantir.test:test = 1.0.0#comment on same line with hash and no spaces",
                StandardCharsets.UTF_8);

        VersionsProps versionsProps = VersionsProps.loadFromFile(propsFile);
        assertThat(versionsProps.getFuzzyResolver().exactMatches()).containsExactly("com.palantir.test:test");
        assertThat(versionsProps.getFuzzyResolver().globs()).isEmpty();
    }

    @Test
    void ignores_commented_out_constraint() throws IOException {
        Path propsFile = tempDir.resolve("versions.props");
        Files.writeString(
                propsFile, "# com.palantir.test:test = 1.0.0\n#com.palantir.test:test2=1.2.3", StandardCharsets.UTF_8);

        VersionsProps versionsProps = VersionsProps.loadFromFile(propsFile);
        assertThat(versionsProps.getFuzzyResolver().exactMatches()).isEmpty();
        assertThat(versionsProps.getFuzzyResolver().globs()).isEmpty();
    }
}
