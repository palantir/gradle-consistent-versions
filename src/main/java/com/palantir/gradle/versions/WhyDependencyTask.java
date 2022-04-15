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
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import com.palantir.gradle.versions.internal.MyModuleVersionIdentifier;
import com.palantir.gradle.versions.lockstate.Dependents;
import com.palantir.gradle.versions.lockstate.FullLockState;
import com.palantir.gradle.versions.lockstate.Line;
import com.palantir.gradle.versions.lockstate.LockStates;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;
import org.gradle.api.DefaultTask;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;

public class WhyDependencyTask extends DefaultTask {

    private final Property<String> hashOption;
    private final Property<FullLockState> fullLockState;
    private Path lockfile;

    public WhyDependencyTask() {
        this.hashOption = getProject().getObjects().property(String.class);
        this.fullLockState = getProject().getObjects().property(FullLockState.class);

        setGroup("Help");
        setDescription("Explains what a specific hash in versions.lock means");
    }

    @Option(option = "hash", description = "Hash from versions.lock to explain")
    public final void setHashOption(String string) {
        hashOption.set(string);
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
        Multimap<String, Line> lineByHash = new ConflictSafeLockFile(lockfile)
                .readLocks().allLines().stream()
                        .collect(Multimaps.toMultimap(Line::dependentsHash, Function.identity(), HashMultimap::create));

        if (!hashOption.isPresent()) {
            Optional<String> example = lineByHash.keySet().stream()
                    .map(h -> ", e.g. './gradlew why --hash " + h + "'")
                    .findFirst();
            throw new RuntimeException(
                    "./gradlew why requires a '--hash <hash>' from versions.lock" + example.orElse(""));
        }

        Set<String> hashes = new LinkedHashSet<>(Splitter.on(",").splitToList(hashOption.get()));

        for (String hash : hashes) {
            lineByHash.get(hash).forEach(line -> {
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
            });
        }
    }
}
