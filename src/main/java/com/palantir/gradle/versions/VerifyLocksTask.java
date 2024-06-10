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

import com.google.common.collect.MapDifference;
import com.google.common.collect.MapDifference.ValueDifference;
import com.google.common.collect.Maps;
import com.google.common.io.Files;
import com.palantir.gradle.versions.internal.MyModuleIdentifier;
import com.palantir.gradle.versions.lockstate.Line;
import com.palantir.gradle.versions.lockstate.LockState;
import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.gradle.api.DefaultTask;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.language.base.plugins.LifecycleBasePlugin;

public class VerifyLocksTask extends DefaultTask {

    private static final String WRITE_LOCKS_SUGGESTION = "./gradlew writeVersionsLocks";
    private final File outputFile;
    private final Property<LockState> persistedLockState;
    private final Property<LockState> currentLockState;

    public VerifyLocksTask() {
        setGroup(LifecycleBasePlugin.VERIFICATION_GROUP);
        setDescription("Verifies that your versions.lock is up to date");

        this.outputFile = new File(getTemporaryDir(), "verified");
        this.persistedLockState = getProject().getObjects().property(LockState.class);
        this.currentLockState = getProject().getObjects().property(LockState.class);
    }

    @Input
    final Property<LockState> getPersistedLockState() {
        return persistedLockState;
    }

    @Input
    final Property<LockState> getCurrentLockState() {
        return currentLockState;
    }

    @OutputFile
    final File getOutputFile() {
        return outputFile;
    }

    @TaskAction
    public final void taskAction() throws IOException {
        verifyLocksForScope(LockState::productionLinesByModuleIdentifier);
        verifyLocksForScope(LockState::testLinesByModuleIdentifier);
        Files.touch(outputFile);
    }

    private void verifyLocksForScope(Function<LockState, SortedMap<MyModuleIdentifier, Line>> getterForScope) {
        MapDifference<MyModuleIdentifier, Line> difference = Maps.difference(
                getterForScope.apply(persistedLockState.get()), getterForScope.apply(currentLockState.get()));

        Set<MyModuleIdentifier> missing = difference.entriesOnlyOnLeft().keySet();
        Validators.checkResultOrThrow(
                missing.isEmpty(),
                "Locked dependencies missing from the resolution result: " + missing + ". Please run '%s'.",
                WRITE_LOCKS_SUGGESTION);

        Set<MyModuleIdentifier> unknown = difference.entriesOnlyOnRight().keySet();
        Validators.checkResultOrThrow(
                unknown.isEmpty(),
                "Found dependencies that were not in the lock state: " + unknown + ". Please run '%s'.",
                WRITE_LOCKS_SUGGESTION);

        Map<MyModuleIdentifier, ValueDifference<Line>> differing = difference.entriesDiffering();
        Validators.checkResultOrThrow(
                differing.isEmpty(),
                "Found dependencies whose dependents changed:\n" + formatDependencyDifferences(differing)
                        + "\nPlease run %s.",
                WRITE_LOCKS_SUGGESTION);
    }

    private static String formatDependencyDifferences(Map<MyModuleIdentifier, ValueDifference<Line>> differing) {
        return differing.entrySet().stream()
                .map(diff -> String.format(
                        "" // to align strings
                                + "-%s\n"
                                + "+%s",
                        diff.getValue().leftValue().stringRepresentation(),
                        diff.getValue().rightValue().stringRepresentation()))
                .collect(Collectors.joining("\n"));
    }
}
