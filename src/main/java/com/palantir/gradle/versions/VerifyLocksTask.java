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
import com.palantir.gradle.versions.internal.MyModuleIdentifier;
import com.palantir.gradle.versions.lockstate.Line;
import com.palantir.gradle.versions.lockstate.LockState;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.gradle.api.DefaultTask;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskAction;
import org.gradle.language.base.plugins.LifecycleBasePlugin;

public class VerifyLocksTask extends DefaultTask {

    private final Property<LockState> persistedLockState;
    private final Property<LockState> currentLockState;

    public VerifyLocksTask() {
        this.persistedLockState = getProject().getObjects().property(LockState.class);
        this.currentLockState = getProject().getObjects().property(LockState.class);

        setGroup(LifecycleBasePlugin.VERIFICATION_GROUP);
        setDescription("Verifies that your versions.lock is consistent with current dependencies");
    }

    public final void persistedLockState(Provider<LockState> provider) {
        this.persistedLockState.set(provider);
    }

    public final void currentLockState(Provider<LockState> provider) {
        this.currentLockState.set(provider);
    }

    @TaskAction
    public final void taskAction() {
        ensureLockStateIsUpToDate(currentLockState.get(), persistedLockState.get());
    }

    private static void ensureLockStateIsUpToDate(LockState currentLockState, LockState persistedLockState) {
        MapDifference<MyModuleIdentifier, Line> difference = Maps.difference(
                persistedLockState.linesByModuleIdentifier(), currentLockState.linesByModuleIdentifier());

        Set<MyModuleIdentifier> missing = difference.entriesOnlyOnLeft().keySet();
        if (!missing.isEmpty()) {
            throw new RuntimeException(
                    "Locked dependencies missing from the resolution result: " + missing + ". "
                            + ". Please run './gradlew --write-locks'.");
        }

        Set<MyModuleIdentifier> unknown = difference.entriesOnlyOnRight().keySet();
        if (!unknown.isEmpty()) {
            throw new RuntimeException(
                    "Found dependencies that were not in the lock state: " + unknown + ". "
                            + "Please run './gradlew --write-locks'.");
        }

        Map<MyModuleIdentifier, ValueDifference<Line>> differing = difference.entriesDiffering();
        if (!differing.isEmpty()) {
            throw new RuntimeException("Found dependencies whose dependents changed:\n"
                    + formatDependencyDifferences(differing) + "\n\n"
                    + "Please run './gradlew --write-locks'.");
        }
    }

    private static String formatDependencyDifferences(
            Map<MyModuleIdentifier, ValueDifference<Line>> differing) {
        return differing.entrySet().stream().map(diff -> String.format("" // to align strings
                        + "-%s\n"
                        + "+%s",
                diff.getValue().leftValue().stringRepresentation(),
                diff.getValue().rightValue().stringRepresentation())).collect(Collectors.joining("\n"));
    }
}
