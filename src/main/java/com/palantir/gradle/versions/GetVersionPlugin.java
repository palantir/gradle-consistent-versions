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

import com.google.common.collect.Iterables;
import com.palantir.gradle.versions.ConsistentVersionsPlugin.GcvAttributes;
import com.palantir.gradle.versions.ConsistentVersionsPlugin.GcvBuildPath;
import com.palantir.gradle.versions.VersionsLockPlugin.GcvUsage;
import java.util.List;
import java.util.Optional;
import javax.inject.Inject;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.result.ResolvedComponentResult;
import org.gradle.api.model.ObjectFactory;

public abstract class GetVersionPlugin implements Plugin<Project> {

    private static final String GET_VERSION_ELEMENTS_CONFIGURATION_NAME = "gcvGetVersionElements";

    static final String GET_VERSIONS_CAPABILITY = "gcv:get-versions:0";

    @Inject
    protected abstract ConfigurationContainer getConfigurations();

    @Inject
    protected abstract ObjectFactory getObjects();

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
            configuration.getAttributes().attribute(VersionsLockPlugin.GCV_USAGE_ATTRIBUTE, GcvUsage.GCV_SOURCE);
            configuration
                    .getAttributes()
                    .attribute(
                            GcvBuildPath.ATTRIBUTE,
                            getObjects().newInstance(GcvAttributes.class).buildPath());
        });

        rootProject
                .getAllprojects()
                .forEach(project -> project.getPluginManager().apply(GetVersionProjectPlugin.class));
    }

    static Optional<String> getOptionalVersion(
            Project project, String group, String name, Configuration configuration) {
        if (GradleWorkarounds.isConfiguring(project.getState())) {
            throw new GradleException(String.format(
                    "Not allowed to call gradle-consistent-versions's getVersion(\"%s\", \"%s\", "
                            + "configurations.%s) "
                            + "at configuration time",
                    group, name, configuration.getName()));
        }

        List<ModuleVersionIdentifier> list =
                configuration.getIncoming().getResolutionResult().getAllComponents().stream()
                        .map(ResolvedComponentResult::getModuleVersion)
                        .filter(item ->
                                item.getGroup().equals(group) && item.getName().equals(name))
                        .toList();

        if (list.isEmpty()) {
            return Optional.empty();
        }

        if (list.size() > 1) {
            throw new GradleException(
                    String.format("Multiple modules matching '%s:%s' in %s: %s", group, name, configuration, list));
        }

        return Optional.of(Iterables.getOnlyElement(list).getVersion());
    }
}
