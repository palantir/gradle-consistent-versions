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

import com.google.common.base.Splitter;
import com.palantir.gradle.versions.internal.MyModuleVersionIdentifier;
import com.palantir.gradle.versions.lockstate.Dependents;
import com.palantir.gradle.versions.lockstate.FullLockState;
import com.palantir.gradle.versions.lockstate.Line;
import com.palantir.gradle.versions.lockstate.LockStates;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.gradle.api.DefaultTask;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;

public class WhyDependencyTask extends DefaultTask {

    private final Property<String> hashOption;
    private final Property<String> dependencyOption;
    private final Property<FullLockState> fullLockState;
    private Path lockfile;

    public WhyDependencyTask() {
        this.hashOption = getProject().getObjects().property(String.class);
        this.dependencyOption = getProject().getObjects().property(String.class);
        this.fullLockState = getProject().getObjects().property(FullLockState.class);

        setGroup("Help");
        setDescription("Explains what a specific hash in versions.lock means");
    }

    @Option(option = "hash", description = "Hash from versions.lock to explain")
    public final void setHashOption(String string) {
        hashOption.set(string);
    }

    @Option(option = "dependency", description = "Dependency from versions.lock to explain")
    public final void setDependencyOption(String string) {
        dependencyOption.set(string);
    }

    public final void lockfile(Path path) {
        this.lockfile = path;
    }

    public final void fullLockState(Provider<FullLockState> provider) {
        this.fullLockState.set(provider);
    }

    @TaskAction
    public final void taskAction() {
        // read the lockfile from disk so that we can fail fast without resolving anything if the hash isn't found
        List<Line> lines = new ConflictSafeLockFile(lockfile).readLocks().allLines();

        if (!hashOption.isPresent() && !dependencyOption.isPresent()) {
            Optional<String> example =
                    lines.stream().findFirst().map(line -> ", e.g. './gradlew why --dependency " + line.name() + "'");
            throw new RuntimeException(
                    "./gradlew why requires a '--dependency <dependency>' option" + example.orElse(""));
        }

        Optional<Set<String>> hashes = Optional.ofNullable(hashOption.getOrNull())
                .map(hash -> Set.copyOf(Splitter.on(",").splitToList(hash)));
        Optional<String> dependency = Optional.ofNullable(dependencyOption.getOrNull());

        for (Line line : lines) {
            if ((hashes.isPresent() && hashes.get().contains(line.dependentsHash()))
                    || (dependency.isPresent() && line.identifier().toString().contains(dependency.get()))) {
                ModuleVersionIdentifier key = MyModuleVersionIdentifier.of(line.group(), line.name(), line.version());

                Optional<Dependents> entry = Stream.of(
                                fullLockState.get().productionDeps(),
                                fullLockState.get().testDeps())
                        .map(state -> state.get(key))
                        .filter(Objects::nonNull)
                        .findFirst();
                Dependents dependents =
                        entry.orElseThrow(() -> new NullPointerException("Unable to find group/name in fullLockState"));

                getLogger().lifecycle("{}", key);
                LockStates.prettyPrintConstraints(dependents).forEach(pretty -> {
                    getLogger().lifecycle("\t{}", pretty);
                });
                getLogger().lifecycle("");
            }
        }
    }
}
