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

import com.palantir.gradle.versions.internal.MyModuleIdentifier;
import java.io.Serializable;
import org.immutables.value.Value;
import org.immutables.value.Value.Lazy;
import org.immutables.value.Value.Parameter;

@Value.Immutable
public interface Line extends Serializable {
    @Parameter
    String group();

    @Parameter
    String name();

    @Lazy
    default MyModuleIdentifier identifier() {
        return MyModuleIdentifier.of(group(), name());
    }

    @Parameter
    String version();

    @Parameter
    int numDependents();

    @Parameter
    String dependentsHash();

    @Lazy
    default String stringRepresentation() {
        return String.format(
                "%s:%s:%s (%s constraints: %s)", group(), name(), version(), numDependents(), dependentsHash());
    }

    @Lazy
    default String stringRepresentationWithoutHash() {
        return String.format("%s:%s:%s", group(), name(), version());
    }
}
