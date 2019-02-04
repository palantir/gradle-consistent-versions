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

package com.palantir.gradle.versions.internal;

import org.gradle.api.artifacts.ModuleIdentifier;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.immutables.value.Value;
import org.immutables.value.Value.Parameter;

@Value.Immutable
@ImmutablesStyle
public abstract class MyModuleVersionIdentifier implements ModuleVersionIdentifier {

    public static MyModuleVersionIdentifier copyOf(ModuleVersionIdentifier moduleVersion) {
        return of(moduleVersion.getGroup(), moduleVersion.getName(), moduleVersion.getVersion());
    }

    @Override
    @Parameter
    public abstract String getGroup();

    @Override
    @Parameter
    public abstract String getName();

    @Override
    @Parameter
    public abstract String getVersion();

    @Override
    public final String toString() {
        return getModule().toString() + ":" + getVersion();
    }

    @Override
    public final ModuleIdentifier getModule() {
        return MyModuleIdentifier.of(getGroup(), getName());
    }

    public static MyModuleVersionIdentifier of(String group, String name, String version) {
        return ImmutableMyModuleVersionIdentifier.of(group, name, version);
    }
}
