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

import com.google.common.collect.Comparators;
import com.google.common.collect.Ordering;
import java.util.Comparator;
import java.util.Optional;
import org.gradle.api.artifacts.ModuleIdentifier;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;

public final class GradleComparators {
    private GradleComparators() {}

    public static final Comparator<ModuleIdentifier> MODULE_IDENTIFIER_COMPARATOR =
            Comparator.comparing(ModuleIdentifier::getGroup).thenComparing(ModuleIdentifier::getName);

    /**
     * Compare {@link ModuleComponentIdentifier} using {@link #MODULE_IDENTIFIER_COMPARATOR}, but all other identifiers
     * using {@link ComponentIdentifier#getDisplayName()}.
     */
    public static final Comparator<ComponentIdentifier> COMPONENT_IDENTIFIER_COMPARATOR = Comparator.comparing(
                    (ComponentIdentifier id) -> tryCast(ModuleComponentIdentifier.class, id), Comparators.emptiesFirst(
                                    Ordering.from(MODULE_IDENTIFIER_COMPARATOR)
                                            .onResultOf(ModuleComponentIdentifier::getModuleIdentifier)))
            .thenComparing(ComponentIdentifier::getDisplayName);

    static <A, T> Optional<T> tryCast(Class<T> to, A value) {
        return to.isInstance(value) ? Optional.of(to.cast(value)) : Optional.empty();
    }
}
