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

import com.google.common.collect.ImmutableMap;
import com.palantir.gradle.versions.VersionsLockPlugin.GcvScope;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.gradle.api.artifacts.ModuleIdentifier;

/** Keeps track of the {@link GcvScope} of direct dependencies. */
public final class DirectDependencyScopes {
    private final ImmutableMap<ModuleIdentifier, GcvScope> map;

    private DirectDependencyScopes(Map<ModuleIdentifier, GcvScope> map) {
        this.map = ImmutableMap.copyOf(map);
    }

    /**
     * Returns the scope if the {@code moduleIdentifier} was used in a direct dependency from one of the locked
     * configurations in this build. Otherwise, returns {@link Optional#empty()}.
     */
    public Optional<GcvScope> getScopeFor(ModuleIdentifier moduleIdentifier) {
        return Optional.ofNullable(map.get(moduleIdentifier));
    }

    public static final class Builder {
        private final Map<ModuleIdentifier, GcvScope> map = new HashMap<>();

        public DirectDependencyScopes build() {
            return new DirectDependencyScopes(map);
        }

        public void record(ModuleIdentifier module, GcvScope scope) {
            map.compute(module, (key, oldScope) ->
                    oldScope != null
                            ? Stream.of(oldScope, scope)
                                    .min(VersionsLockPlugin.GCV_SCOPE_COMPARATOR)
                                    .get()
                            : scope);
        }
    }
}
