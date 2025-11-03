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

import com.google.common.base.Splitter;
import com.palantir.gradle.versions.lockstate.LockState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConflictSafeLockFileTest {
    @TempDir
    Path tempDir;

    Path preBlankLinesLockFilePath;
    Path currentLockFilePath;

    private static final String PRE_BLANK_LINES_LOCK_FILE_CONTENT = """
        # Run ./gradlew writeVersionsLocks to regenerate this file
        aopalliance:aopalliance:1.0 (1 constraints: 170a83ac)
        ch.qos.logback:logback-core:1.2.3 (4 constraints: 5e330891)
        com.fasterxml.jackson.module:jackson-module-parameter-names:2.9.3 (1 constraints: 880ec14f)

        [Test dependencies]
        com.palantir.conjure.java.api:test-utils:2.2.0 (1 constraints: 6b122d13)
        junit:junit:4.12 (4 constraints: 4734a44f)
        """;

    private static final @SuppressWarnings("checkstyle:RegexpMultiline") String CURRENT_LOCK_FILE_CONTENT = """
        # Run ./gradlew writeVersionsLocks to regenerate this file. Blank lines are to minimize merge conflicts.

        aopalliance:aopalliance:1.0 (1 constraints: 170a83ac)

        ch.qos.logback:logback-core:1.2.3 (4 constraints: 5e330891)

        com.fasterxml.jackson.module:jackson-module-parameter-names:2.9.3 (1 constraints: 880ec14f)



        [Test dependencies]

        com.palantir.conjure.java.api:test-utils:2.2.0 (1 constraints: 6b122d13)

        junit:junit:4.12 (4 constraints: 4734a44f)
        """;

    @BeforeEach
    void beforeEach() throws IOException {
        preBlankLinesLockFilePath = tempDir.resolve("pre-blanks-lines.lock");
        currentLockFilePath = tempDir.resolve("current.lock");

        Files.writeString(preBlankLinesLockFilePath, PRE_BLANK_LINES_LOCK_FILE_CONTENT);
        Files.writeString(currentLockFilePath, CURRENT_LOCK_FILE_CONTENT);
    }

    @Test
    void should_parse_a_lock_file_pre_blanks_lines_successfully() {
        ConflictSafeLockFile preBlankLinesFile = new ConflictSafeLockFile(preBlankLinesLockFilePath);

        LockState locks = preBlankLinesFile.readLocks();

        assertThat(locks.productionLinesByModuleIdentifier()).hasSize(3);
        assertThat(locks.testLinesByModuleIdentifier()).hasSize(2);
    }

    @Test
    void should_parse_a_lock_file_post_blanks_lines_successfully() {
        ConflictSafeLockFile preBlankLinesFile = new ConflictSafeLockFile(currentLockFilePath);

        LockState locks = preBlankLinesFile.readLocks();

        assertThat(locks.productionLinesByModuleIdentifier()).hasSize(3);
        assertThat(locks.testLinesByModuleIdentifier()).hasSize(2);
    }

    @Test
    void should_preserve_exact_content_when_reading_and_writing_current_sample_lock_file() {
        ConflictSafeLockFile currentFile = new ConflictSafeLockFile(currentLockFilePath);
        LockState lockState = currentFile.readLocks();

        Path expectedContentPath = tempDir.resolve("expected.lock");
        ConflictSafeLockFile outputLockFile = new ConflictSafeLockFile(expectedContentPath);
        outputLockFile.writeLocks(lockState);

        assertThat(currentLockFilePath).hasSameTextualContentAs(expectedContentPath);
    }

    @Test
    void can_migrate_from_pre_blank_lines_to_post_blanks_lines() {
        ConflictSafeLockFile preBlankLinesFile = new ConflictSafeLockFile(preBlankLinesLockFilePath);
        LockState lockState = preBlankLinesFile.readLocks();

        Path migratedLockFilePath = tempDir.resolve("migrated.lock");
        ConflictSafeLockFile migratedLockFile = new ConflictSafeLockFile(migratedLockFilePath);

        migratedLockFile.writeLocks(lockState);

        assertThat(migratedLockFilePath).hasContent(CURRENT_LOCK_FILE_CONTENT);
    }

    @Test
    void ensure_we_dont_break_renovate_bot_unintentionally() {
        // renovate-bot supports upgrading GCV but uses this regex to determine if GCV is being used:
        // https://github.com/renovatebot/renovate/blob/f94c2d3ce7a86f3ef168d5db118bbea41def573e/
        // lib/modules/manager/gradle/extract/consistent-versions-plugin.ts#L12
        // If this is changed, renovate-bot will break. Breaking is an option if we need to - we just shouldn't
        // do it unintentionally and probably try to PR a fix.
        Pattern renovateBotHeadfilePattern = Pattern.compile(
                "^# Run \\./gradlew (?:--write-locks|writeVersionsLock|writeVersionsLocks) to regenerate this file");

        String firstLine =
                Splitter.on('\n').splitToList(CURRENT_LOCK_FILE_CONTENT).get(0);

        // javascript's regex `match` function renovate uses is like Java's `Pattern#find` in that it doesn't
        // need to match the whole string.
        assertThat(renovateBotHeadfilePattern.matcher(firstLine).find()).isTrue();
    }
}
