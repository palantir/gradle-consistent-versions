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

import static java.util.stream.Collectors.toList;

import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.Iterables;
import groovy.lang.Closure;
import java.util.List;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.result.ResolvedComponentResult;

public final class GetVersionPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        project.getExtensions().getExtraProperties().set("getVersion", new Closure<String>(project, project) {

            /**
             * Groovy will invoke this method if they just supply one arg, e.g. 'com.google.guava:guava'.
             * This is the preferred signature because it's shortest.
             */
            public String doCall(Object moduleVersion) {
                return doCall(moduleVersion, project.getRootProject()
                        .getConfigurations()
                        .getByName(VersionsLockPlugin.UNIFIED_CLASSPATH_CONFIGURATION_NAME));
            }

            /** Find a version from another configuration, e.g. from the gradle-docker plugin. */
            public String doCall(Object moduleVersion, Configuration configuration) {
                List<String> strings = Splitter.on(':').splitToList(moduleVersion.toString());
                Preconditions.checkState(
                        strings.size() == 2,
                        "Expected 'group:name', found: %s",
                        moduleVersion.toString());

                return getVersion(project, strings.get(0), strings.get(1), configuration);
            }

            /** This matches the signature of nebula's dependencyRecommendations.getRecommendedVersion. */
            public String doCall(String group, String name) {
                return getVersion(project, group, name, project.getRootProject()
                        .getConfigurations()
                        .getByName(VersionsLockPlugin.UNIFIED_CLASSPATH_CONFIGURATION_NAME));
            }
        });
    }

    private static String getVersion(Project project, String group, String name, Configuration configuration) {
        Preconditions.checkState(
                !GradleWorkarounds.isConfiguring(project.getState()),
                "Not allowed to call getVersion at configuration time - try using a doLast block");

        List<ModuleVersionIdentifier> list = configuration.getIncoming()
                .getResolutionResult()
                .getAllComponents()
                .stream()
                .map(ResolvedComponentResult::getModuleVersion)
                .filter(item -> item.getGroup().equals(group) && item.getName().equals(name))
                .collect(toList());

        if (list.isEmpty()) {
            throw notFound(group, name, configuration);
        }

        if (list.size() > 1) {
            throw new GradleException(String.format("Multiple modules matching '%s:%s' in %s: %s",
                    group, name, configuration, list));
        }

        return Iterables.getOnlyElement(list).getVersion();
    }

    private static GradleException notFound(String group, String name, Configuration configuration) {
        List<ModuleVersionIdentifier> actual = configuration.getIncoming()
                .getResolutionResult()
                .getAllComponents()
                .stream()
                .map(ResolvedComponentResult::getModuleVersion)
                .collect(toList());
        return new GradleException(String.format(
                "Unable to find '%s:%s' in %s: %s. This may happen if you specify the version in versions.props but do"
                        + " not have a dependency in the configuration",
                group, name, configuration, actual));
    }
}
