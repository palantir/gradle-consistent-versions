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

import java.util.stream.Stream;
import javax.inject.Inject;
import org.gradle.api.Project;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.SourceSet;

public class VersionsLockExtension {
    private final SetProperty<String> productionConfigurations;
    private final SetProperty<String> testConfigurations;

    public enum Scope {
        PRODUCTION,
        TEST
    }

    @Inject
    public VersionsLockExtension(Project project) {
        this.productionConfigurations = project.getObjects().setProperty(String.class).empty();
        this.testConfigurations = project.getObjects().setProperty(String.class).empty();
    }

    final SetProperty<String> getProductionConfigurations() {
        return productionConfigurations;
    }

    final SetProperty<String> getTestConfigurations() {
        return testConfigurations;
    }

    /**
     * Add the {@code configuration} to the set of configurations that should be locked for the given {@code scope}.
     */
    public final void lockConfiguration(Scope scope, String configuration) {
        switch (scope) {
            case PRODUCTION:
                productionConfigurations.add(configuration);
                break;
            case TEST:
                testConfigurations.add(configuration);
                break;
        }
    }

    /**
     * Add the {@code configuration} to the set of configurations that should be locked for the given {@code scope}.
     */
    public final void lockSourceSet(Scope scope, SourceSet sourceSet) {
        Stream
                .of(sourceSet.getCompileClasspathConfigurationName(), sourceSet.getRuntimeClasspathConfigurationName())
                .forEach(conf -> lockConfiguration(scope, conf));
    }
}
