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

import com.palantir.gradle.versions.lockstate.FullLockState;
import java.nio.file.Path;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

abstract class WriteVersionsLockTask extends DefaultTask {
    @OutputFile
    abstract RegularFileProperty getLockRootLockFile();

    @Input
    abstract Property<FullLockState> getFullLockState();

    @TaskAction
    public final void writeLocks() {
        Path rootLockFile = getLockRootLockFile().getAsFile().get().toPath();

        // Triggers evaluation of unifiedClasspath
        new ConflictSafeLockFile(rootLockFile).writeLocks(getFullLockState().get());
        getLogger().lifecycle("Finished writing lock state to {}", rootLockFile);
    }
}
