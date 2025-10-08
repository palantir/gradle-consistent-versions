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
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ModuleVersionSelector;
import org.gradle.api.artifacts.ResolvableDependencies;
import org.gradle.api.plugins.JavaPlugin;

/**
 * This plugin exists in order to ensure versions in legacy java configurations (that are still
 * {@link Configuration#isCanBeResolved() resolvable} for compatibility reasons) can be resolved, and that they use the
 * locked versions.
 */
public class FixLegacyJavaConfigurationsPlugin implements Plugin<Project> {
    @Override
    public final void apply(Project project) {
        // ConsistentVersionsPlugin should ensure that we only get applied onto java projects
        if (!project.getPlugins().hasPlugin(JavaPlugin.class)) {
            throw new GradleException("FixLegacyJavaConfigurationsPlugin must be applied after 'java' / JavaPlugin");
        }

        if (VersionsLockPlugin.isIgnoreLockFile(project)) {
            return;
        }

        Configuration unifiedClasspath = project.getRootProject()
                .getConfigurations()
                .findByName(VersionsLockPlugin.UNIFIED_CLASSPATH_CONFIGURATION_NAME);
        Preconditions.checkNotNull(
                unifiedClasspath, "FixLegacyJavaConfigurationsPlugin must be applied after VersionsLockPlugin");

        fixLegacyResolvableJavaConfigurations(project, unifiedClasspath);
    }

    private void fixLegacyResolvableJavaConfigurations(Project project, Configuration unifiedClasspath) {
        // TODO(fwindheuser): Remove compile and runtime after stating to build with Gradle 7+
        Stream.of("compile", "runtime", JavaPlugin.COMPILE_ONLY_CONFIGURATION_NAME)
                .map(project.getConfigurations()::findByName)
                .filter(Objects::nonNull)
                .forEach(conf -> {
                    injectVersions(
                            conf,
                            (group, name) ->
                                    GetVersionPlugin.getOptionalVersion(project, group, name, unifiedClasspath));
                });
    }

    private interface GetVersion {
        Optional<String> getVersion(String group, String name);
    }

    /** Inject versions of _all_ dependencies into the given {@code conf}, by polling the {@code getVersion}. */
    private void injectVersions(Configuration conf, GetVersion getVersion) {
        ResolvableDependencies incoming = conf.getIncoming();
        incoming.beforeResolve(dependencies -> {
            // Bail if this is a copied configuration.
            if (incoming != dependencies) {
                return;
            }
            // Code adapted from:
            // https://github.com/nebula-plugins/nebula-dependency-recommender-plugin/blob/64ed7c6853f80b909918e6a595231a5e9803ae8b/src/main/groovy/netflix/nebula/dependency/recommender/DependencyRecommendationsPlugin.java
            conf.getResolutionStrategy().eachDependency(details -> {
                ModuleVersionSelector requested = details.getTarget();

                // don't interfere with the way forces trump everything
                for (ModuleVersionSelector force : conf.getResolutionStrategy().getForcedModules()) {
                    if (requested.getGroup().equals(force.getGroup())
                            && requested.getName().equals(force.getName())) {
                        details.because(String.format(
                                "Would have recommended a version for %s:%s, but a force is in place",
                                requested.getGroup(), requested.getName()));
                        return;
                    }
                }

                getVersion
                        .getVersion(
                                details.getRequested().getGroup(),
                                details.getRequested().getName())
                        .ifPresent(ver -> {
                            details.useVersion(ver);
                            details.because("Forced by gradle-consistent-versions versions.lock");
                        });
            });
        });
    }
}
