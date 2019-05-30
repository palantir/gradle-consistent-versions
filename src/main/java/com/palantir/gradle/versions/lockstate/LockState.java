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

import com.google.common.collect.ImmutableSortedMap;
import com.palantir.gradle.versions.GradleComparators;
import com.palantir.gradle.versions.internal.MyModuleIdentifier;
import java.io.Serializable;
import java.util.List;
import java.util.SortedMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.immutables.value.Value;
import org.immutables.value.Value.Parameter;

/**
 * Holds the state of dependencies that should be written to disk when gradle is invoked with {@code --write-locks}.
 */
@Value.Immutable
public interface LockState extends Serializable {

    @Parameter
    List<Line> lines();

    /** Mapping from {@code group:artifact} to the full line. */
    @Value.Lazy
    default SortedMap<MyModuleIdentifier, Line> linesByModuleIdentifier() {
        return lines().stream().collect(ImmutableSortedMap.toImmutableSortedMap(
                GradleComparators.MODULE_IDENTIFIER_COMPARATOR,
                Line::identifier,
                Function.identity()));
    }

    static LockState from(Stream<Line> lines) {
        return ImmutableLockState.of(lines.collect(Collectors.toList()));
    }
}
