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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.Set;
import java.util.stream.Stream;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.tasks.SourceSet;

final class GradleConfigurations {
    /**
     * Deprecated sourcesets of the compile and runtime configuration, to be removed with Gradle 7.
     * The full configuration name follows the naming scheme of "$taskBaseName + capitalize($configurationName)"
     * see {@link org.gradle.api.internal.tasks.DefaultSourceSet#configurationNameOf}.
     */
    private static final ImmutableList<String> DEPRECATED_SOURCESET_SUFFIXES = ImmutableList.of("Compile", "Runtime");

    /**
     * Filters out both the unresolvable configurations but also the legacy java configurations that should not be
     * resolved.
     */
    public static Stream<Configuration> getResolvableConfigurations(Project project) {
        Set<String> legacyJavaConfigurations = getLegacyJavaConfigurations(project);
        return project.getConfigurations().stream()
                .filter(Configuration::isCanBeResolved)
                .filter(conf -> !legacyJavaConfigurations.contains(conf.getName()))
                .filter(conf -> DEPRECATED_SOURCESET_SUFFIXES.stream()
                        .noneMatch(suffix -> conf.getName().endsWith(suffix)));
    }

    /**
     * Get the legacy java configurations that should not be resolved. If the project does not have the java plugin
     * applied, this returns an empty set.
     */
    private static Set<String> getLegacyJavaConfigurations(Project project) {
        JavaPluginConvention javaConvention = project.getConvention().findPlugin(JavaPluginConvention.class);
        if (javaConvention == null) {
            return ImmutableSet.of();
        }
        return ImmutableSet.<String>builder()
                .add("default")
                .addAll(javaConvention.getSourceSets().stream()
                        .map(SourceSet::getCompileOnlyConfigurationName)
                        .iterator())
                .build();
    }

    private GradleConfigurations() {}
}
