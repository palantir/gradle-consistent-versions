/*
 * (c) Copyright 2020 Palantir Technologies Inc. All rights reserved.
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

import org.gradle.api.Action;
import org.gradle.api.artifacts.DependencyConstraint;
import org.gradle.api.artifacts.MutableVersionConstraint;
import org.gradle.api.artifacts.dsl.DependencyConstraintHandler;

interface DependencyConstraintCreator {
    default DependencyConstraint create(Object notation) {
        return create(notation, _constraint -> {});
    }

    DependencyConstraint create(Object notation, Action<? super DependencyConstraint> action);

    void lockVersion(MutableVersionConstraint versionConstraint, String version);

    static DependencyConstraintCreator strict(DependencyConstraintHandler handler) {
        return new DependencyConstraintCreator() {
            @Override
            public DependencyConstraint create(Object notation, Action<? super DependencyConstraint> action) {
                return handler.create(notation, action);
            }

            @Override
            public void lockVersion(MutableVersionConstraint versionConstraint, String version) {
                versionConstraint.strictly(version);
            }
        };
    }

    static DependencyConstraintCreator required(DependencyConstraintHandler handler) {
        return new DependencyConstraintCreator() {
            @Override
            public DependencyConstraint create(Object notation, Action<? super DependencyConstraint> action) {
                return handler.create(notation, action);
            }

            @Override
            public void lockVersion(MutableVersionConstraint versionConstraint, String version) {
                versionConstraint.prefer(version);
            }
        };
    }
}
