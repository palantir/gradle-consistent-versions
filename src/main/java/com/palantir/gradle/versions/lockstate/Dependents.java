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

import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import org.gradle.api.artifacts.VersionConstraint;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.immutables.value.Value;
import org.immutables.value.Value.Parameter;

@Value.Immutable
public interface Dependents {

    @Parameter
    SortedMap<ComponentIdentifier, Set<VersionConstraint>> get();

    @Value.Derived
    default SortedSet<VersionConstraint> projectConstraints() {
        return Maps.filterKeys(get(), k -> k instanceof ProjectComponentIdentifier).values().stream()
                .flatMap(Set::stream)
                .collect(ImmutableSortedSet.toImmutableSortedSet(Comparator.comparing(VersionConstraint::toString)));
    }

    @Value.Derived
    default Map<ComponentIdentifier, Set<VersionConstraint>> nonProjectConstraints() {
        return Maps.filterKeys(get(), k -> !(k instanceof ProjectComponentIdentifier));
    }

    static Dependents of(SortedMap<ComponentIdentifier, Set<VersionConstraint>> dependents) {
        return ImmutableDependents.of(dependents);
    }
}
