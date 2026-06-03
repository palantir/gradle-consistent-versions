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

import com.palantir.gradle.versions.ConsistentVersionsPlugin.GcvAttributes;
import javax.inject.Inject;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.tasks.Nested;

public abstract class GetVersionPlugin implements Plugin<Project> {

    private static final String GET_VERSION_ELEMENTS_CONFIGURATION_NAME = "gcvGetVersionElements";

    static final String GET_VERSIONS_CAPABILITY = "gcv:get-versions:0";

    @Nested
    protected abstract GcvAttributes getAttributes();

    @Inject
    protected abstract ConfigurationContainer getConfigurations();

    @Override
    public final void apply(Project rootProject) {
        if (!rootProject.getRootProject().equals(rootProject)) {
            throw new GradleException("GetVersionPlugin must be applied only to root project");
        }

        getConfigurations().register(GET_VERSION_ELEMENTS_CONFIGURATION_NAME, configuration -> {
            configuration.setCanBeConsumed(true);
            configuration.setCanBeResolved(false);
            configuration.extendsFrom(getConfigurations()
                    .getByName(VersionsLockPlugin.UNIFIED_CLASSPATH_DEPENDENCIES_CONFIGURATION_NAME));
            configuration.getOutgoing().capability(GET_VERSIONS_CAPABILITY);
            configuration.attributes(getAttributes()::configureGcvBaseAttributes);
        });

        rootProject
                .getAllprojects()
                .forEach(project -> project.getPluginManager().apply(GetVersionProjectPlugin.class));
    }
}
