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

import com.palantir.gradle.versions.internal.MyModuleVersionIdentifier;
import java.util.Map;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentSelector;
import org.gradle.api.artifacts.result.ResolvedComponentResult;
import org.gradle.api.artifacts.result.ResolvedDependencyResult;
import org.immutables.value.Value;

@Value.Immutable
public interface FullLockState {
    /**
     * Map of a {@link ResolvedComponentResult#getModuleVersion resolved component's module & version} -&gt;
     * {@link ComponentIdentifier} of {@link ResolvedDependencyResult#getFrom component that requested it} -&gt;
     * {@link ModuleComponentSelector#getVersionConstraint version constraint for that dependency}.
     */
    Map<MyModuleVersionIdentifier, Dependents> lines();

    class Builder extends ImmutableFullLockState.Builder {}

    static Builder builder() {
        return new Builder();
    }
}
