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

import com.palantir.gradle.versions.lockstate.LockState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConflictSafeLockFileTest {

    private static final Path CURRENT_SAMPLE_LOCK_FILE = Paths.get("src/test/resources/sample-versions-current.lock");

    @Test
    void should_parse_a_lock_file_pre_blanks_lines_successfully() {
        ConflictSafeLockFile file =
                new ConflictSafeLockFile(Paths.get("src/test/resources/sample-versions-pre-blank-lines.lock"));

        LockState locks = file.readLocks();

        assertThat(locks.productionLinesByModuleIdentifier()).hasSize(27);
        assertThat(locks.testLinesByModuleIdentifier()).hasSize(16);
    }

    @Test
    void should_parse_a_lock_file_post_blanks_lines_successfully() {
        ConflictSafeLockFile file = new ConflictSafeLockFile(CURRENT_SAMPLE_LOCK_FILE);

        LockState locks = file.readLocks();

        assertThat(locks.productionLinesByModuleIdentifier()).hasSize(27);
        assertThat(locks.testLinesByModuleIdentifier()).hasSize(16);
    }

    @Test
    void should_preserve_exact_content_when_reading_and_writing_current_sample_lock_file(@TempDir Path tempDir) {
        ConflictSafeLockFile originalFile = new ConflictSafeLockFile(CURRENT_SAMPLE_LOCK_FILE);
        LockState lockState = originalFile.readLocks();

        Path expectedContentPath = tempDir.resolve("output.lock");
        ConflictSafeLockFile outputLockFile = new ConflictSafeLockFile(expectedContentPath);
        outputLockFile.writeLocks(lockState);

        assertThat(CURRENT_SAMPLE_LOCK_FILE).hasSameTextualContentAs(expectedContentPath);
    }

    @Test
    void ensure_we_dont_break_renovate_bot_unintentionally() throws IOException {
        // renovate-bot supports upgrade GCV but uses this regex to determine if GCV is being used:
        // https://github.com/renovatebot/renovate/blob/f94c2d3ce7a86f3ef168d5db118bbea41def573e/
        // lib/modules/manager/gradle/extract/consistent-versions-plugin.ts#L12
        // If this is changed, renovate-bot will break. Breaking is an option if we need to - we just shouldn't
        // do it unintentionally and probably try to PR a fix.
        Pattern renovateBotHeadfilePattern = Pattern.compile(
                "^# Run \\./gradlew (?:--write-locks|writeVersionsLock|writeVersionsLocks) to regenerate this file");

        String firstLine = Files.readAllLines(CURRENT_SAMPLE_LOCK_FILE).get(0);

        assertThat(firstLine).matches(renovateBotHeadfilePattern);
    }
}
