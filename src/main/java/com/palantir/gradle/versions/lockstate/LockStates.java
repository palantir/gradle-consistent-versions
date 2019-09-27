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

package com.palantir.gradle.versions.lockstate;

import static java.util.stream.Collectors.toList;

import com.google.common.collect.Maps;
import com.google.common.collect.Streams;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.palantir.gradle.versions.internal.MyModuleVersionIdentifier;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.VersionConstraint;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LockStates {
    private static final Logger log = LoggerFactory.getLogger(LockStates.class);

    private LockStates() {}

    /**
     * Convert the richer {@link FullLockState} to a {@link LockState} that maps exactly to the contents of the file.
     */
    public static LockState toLockState(FullLockState fullLockState) {
        return LockState.from(computeLines(fullLockState.productionDeps()), computeLines(fullLockState.testDeps()));
    }

    public static Stream<Line> computeLines(Map<MyModuleVersionIdentifier, Dependents> deps) {
        return deps.entrySet().stream().map(entry -> componentWithDependentsToLine(entry.getKey(), entry.getValue()));
    }

    private static Line componentWithDependentsToLine(ModuleVersionIdentifier component, Dependents dependents) {
        List<String> all = prettyPrintConstraints(dependents);

        Hasher hasher = Hashing.adler32().newHasher();
        all.forEach(item -> hasher.putString(item, StandardCharsets.UTF_8));
        HashCode hash = hasher.hash();

        Line line = ImmutableLine.of(component.getGroup(), component.getName(), component.getVersion(), all.size(), hash
                .toString());
        log.info("{}: {}", line.stringRepresentation(), all);
        return line;
    }

    // turns a collections of VersionConstraints into a nice string like: "group:name -> {0.9, 0.8+}"
    public static List<String> prettyPrintConstraints(Dependents dependents) {
        Stream<Entry<String, Collection<VersionConstraint>>> constraintEntries = Streams.concat(
                dependents.projectConstraints().isEmpty()
                        ? Stream.of()
                        : Stream.of(Maps.immutableEntry("projects", dependents.projectConstraints())),
                dependents.nonProjectConstraints().entrySet().stream().map(e -> Maps.immutableEntry(
                        formatComponentIdentifier(e.getKey()), e.getValue())));

        return constraintEntries
                .map(e -> {
                    List<String> constraintsStr = e.getValue().stream()
                            .map(VersionConstraint::toString)
                            .filter(string -> !string.isEmpty()) // toString is empty if the constraint is a no-op
                            .collect(toList());

                    if (constraintsStr.isEmpty()) {
                        return Optional.<String>empty();
                    } else if (constraintsStr.size() == 1) {
                        return Optional.of(e.getKey() + " -> " + constraintsStr.get(0));
                    } else {
                        return Optional.of(
                                e.getKey()
                                        + " -> "
                                        + constraintsStr.stream().collect(Collectors.joining(", ", "{", "}")));
                    }
                })
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(toList());
    }

    private static String formatComponentIdentifier(ComponentIdentifier id) {
        if (id instanceof ModuleComponentIdentifier) {
            // We don't include the version, as conflicts in the version would show up on the line for that version.
            return ((ModuleComponentIdentifier) id).getModuleIdentifier().toString();
        }
        return id.getDisplayName();
    }
}
