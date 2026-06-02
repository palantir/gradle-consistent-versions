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

import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;
import com.palantir.gradle.versions.ConsistentVersionsPlugin.GcvAttributes;
import com.palantir.gradle.versions.ConsistentVersionsPlugin.GcvBuildPath;
import com.palantir.gradle.versions.VersionsLockPlugin.GcvUsage;
import groovy.lang.Closure;
import java.util.List;
import java.util.stream.Collectors;
import javax.inject.Inject;
import org.gradle.api.GradleException;
import org.gradle.api.NamedDomainObjectProvider;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.ProjectDependency;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.artifacts.result.ResolvedComponentResult;
import org.gradle.api.model.ObjectFactory;

public abstract class GetVersionProjectPlugin implements Plugin<Project> {

    private static final String GET_VERSIONS_CONFIGURATION_NAME = "gcvGetVersions";

    @Inject
    protected abstract ObjectFactory getObjects();

    @Inject
    protected abstract DependencyHandler getDependencies();

    @Inject
    protected abstract ConfigurationContainer getConfigurations();

    @Override
    public final void apply(Project project) {
        NamedDomainObjectProvider<Configuration> getVersions = getConfigurations()
                .register(GET_VERSIONS_CONFIGURATION_NAME, configuration -> {
                    configuration.setCanBeConsumed(false);
                    configuration.setCanBeResolved(true);
                    configuration
                            .getAttributes()
                            .attribute(VersionsLockPlugin.GCV_USAGE_ATTRIBUTE, GcvUsage.GCV_SOURCE);
                    configuration
                            .getAttributes()
                            .attribute(
                                    GcvBuildPath.ATTRIBUTE,
                                    getObjects()
                                            .newInstance(GcvAttributes.class)
                                            .buildPath());

                    ProjectDependency rootDependency =
                            (ProjectDependency) getDependencies().project(ImmutableMap.of("path", ":"));
                    rootDependency.capabilities(
                            handler -> handler.requireCapabilities(GetVersionPlugin.GET_VERSIONS_CAPABILITY));
                    configuration.getDependencies().add(rootDependency);
                });

        project.getExtensions().getExtraProperties().set("getVersion", new Closure<String>(project, project) {
            /**
             * Groovy will invoke this method if they just supply one arg, e.g. 'com.google.guava:guava'. This is the
             * preferred signature because it's shortest.
             */
            @SuppressWarnings("for-rollout:UnusedMethod")
            public String doCall(Object moduleVersion) {
                return doCall(moduleVersion, getVersions.get());
            }

            /** Find a version from another configuration, e.g. from the gradle-docker plugin. */
            @SuppressWarnings("for-rollout:FloggerArgumentToString")
            public String doCall(Object moduleVersion, Configuration configuration) {
                List<String> strings = Splitter.on(':').splitToList(moduleVersion.toString());
                Preconditions.checkState(
                        strings.size() == 2, "Expected 'group:name', found: %s", moduleVersion.toString());

                return getVersion(project, strings.get(0), strings.get(1), configuration);
            }

            /** This matches the signature of nebula's dependencyRecommendations.getRecommendedVersion. */
            @SuppressWarnings("for-rollout:UnusedMethod")
            public String doCall(String group, String name) {
                return getVersion(project, group, name, getVersions.get());
            }

            @SuppressWarnings("for-rollout:UnusedMethod")
            public String doCall(String group, String name, Configuration configuration) {
                return getVersion(project, group, name, configuration);
            }
        });
    }

    private static String getVersion(Project project, String group, String name, Configuration configuration) {
        return GetVersionPlugin.getOptionalVersion(project, group, name, configuration)
                .orElseThrow(() -> notFound(group, name, configuration));
    }

    private static GradleException notFound(String group, String name, Configuration configuration) {
        String actual = configuration.getIncoming().getResolutionResult().getAllComponents().stream()
                .map(ResolvedComponentResult::getModuleVersion)
                .map(mvi -> String.format("\t- %s:%s:%s", mvi.getGroup(), mvi.getName(), mvi.getVersion()))
                .collect(Collectors.joining("\n"));
        return new GradleException(String.format(
                "Unable to find '%s:%s' in %s. This may happen if you specify the version in versions.props"
                        + " but do not have a dependency in the configuration. The configuration contained:\n"
                        + "%s",
                group, name, configuration, actual));
    }
}
