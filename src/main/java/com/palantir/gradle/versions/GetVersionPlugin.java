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
import java.util.Optional;
import java.util.stream.Collectors;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.DependencyConstraint;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.result.ResolvedComponentResult;
import org.gradle.api.provider.Provider;

public final class GetVersionPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        project.getExtensions().getExtraProperties().set("getVersion", new Closure<String>(project, project) {
            /**
             * Groovy will invoke this method if they just supply one arg, e.g. 'com.google.guava:guava'. This is the
             * preferred signature because it's shortest.
             */
            @SuppressWarnings("for-rollout:UnusedMethod")
            public String doCall(Object moduleVersion) {
                List<String> strings = splitModuleVersion(moduleVersion);
                return versionProviderFromLockConstraints(project, strings.get(0), strings.get(1))
                        .get();
            }

            /** Find a version from another configuration, e.g. from the gradle-docker plugin. */
            @SuppressWarnings("for-rollout:UnusedMethod")
            public String doCall(Object moduleVersion, Configuration configuration) {
                List<String> strings = splitModuleVersion(moduleVersion);
                return getVersion(project, strings.get(0), strings.get(1), configuration);
            }

            /** This matches the signature of nebula's dependencyRecommendations.getRecommendedVersion. */
            @SuppressWarnings("for-rollout:UnusedMethod")
            public String doCall(String group, String name) {
                return versionProviderFromLockConstraints(project, group, name).get();
            }

            @SuppressWarnings("for-rollout:UnusedMethod")
            public String doCall(String group, String name, Configuration configuration) {
                return getVersion(project, group, name, configuration);
            }
        });
    }

    @SuppressWarnings("for-rollout:FloggerArgumentToString")
    private static List<String> splitModuleVersion(Object moduleVersion) {
        List<String> strings = Splitter.on(':').splitToList(moduleVersion.toString());
        Preconditions.checkState(strings.size() == 2, "Expected 'group:name', found: %s", moduleVersion.toString());
        return strings;
    }

    private static String getVersion(Project project, String group, String name, Configuration configuration) {
        return getOptionalVersion(project, group, name, configuration)
                .orElseThrow(() -> notFound(group, name, configuration));
    }

    /**
     * Look up the locked version of {@code group:name} from the strict constraints that gradle-consistent-versions
     * derives from {@code versions.lock}, without resolving any configuration.
     *
     * <p>Previously this resolved the root project's {@code unifiedClasspath} configuration. Gradle 9 forbids resolving
     * another project's configuration from a task (it fails with "Resolution of the configuration was attempted without
     * an exclusive lock"), which broke calls from subprojects. The locked versions are already held, fully resolved, as
     * the strict dependency constraints of the root project's {@code gcvLocks} platform, so we read them from there
     * instead. Reading the declared constraints is plain model access rather than resolution, so it is safe to do from
     * any project.
     *
     * <p>The lookup is built lazily off the {@code gcvLocks} {@link org.gradle.api.NamedDomainObjectProvider} so that
     * the platform is only realised (and read) when the provider is queried — which we defer to the last possible
     * moment, when {@code getVersion} is actually invoked at execution time.
     */
    private static Provider<String> versionProviderFromLockConstraints(Project project, String group, String name) {
        return project.getRootProject()
                .getConfigurations()
                .named(VersionsLockPlugin.GCV_LOCKS_CONFIGURATION_NAME)
                .map(gcvLocks -> getOptionalVersionFromLockConstraints(project, gcvLocks, group, name)
                        .orElseThrow(() -> new GradleException(String.format(
                                "Unable to find '%s:%s' in configuration ':%s'. This may happen if you specify the"
                                        + " version in versions.props but do not have a dependency on it anywhere in"
                                        + " the project.",
                                group, name, VersionsLockPlugin.UNIFIED_CLASSPATH_CONFIGURATION_NAME))));
    }

    private static Optional<String> getOptionalVersionFromLockConstraints(
            Project project, Configuration gcvLocks, String group, String name) {
        // Guard against configuration-time calls: the lock constraints are only populated in afterEvaluate.
        if (GradleWorkarounds.isConfiguring(project.getState())) {
            throw new GradleException(String.format(
                    "Not allowed to call gradle-consistent-versions's getVersion(\"%s\", \"%s\") at configuration time",
                    group, name));
        }

        List<String> versions = gcvLocks.getDependencyConstraints().stream()
                .filter(constraint -> constraint.getGroup().equals(group)
                        && constraint.getName().equals(name))
                .map(GetVersionPlugin::lockedVersionOf)
                .collect(toList());

        if (versions.isEmpty()) {
            return Optional.empty();
        }

        if (versions.size() > 1) {
            throw new GradleException(
                    String.format("Multiple lock constraints matching '%s:%s': %s", group, name, versions));
        }

        return Optional.of(Iterables.getOnlyElement(versions));
    }

    private static String lockedVersionOf(DependencyConstraint constraint) {
        // The lock constraints are created with 'strictly', but fall back to the required version to be safe.
        String strictVersion = constraint.getVersionConstraint().getStrictVersion();
        return strictVersion.isEmpty() ? constraint.getVersionConstraint().getRequiredVersion() : strictVersion;
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
                        .collect(toList());

        if (list.isEmpty()) {
            return Optional.empty();
        }

        if (list.size() > 1) {
            throw new GradleException(
                    String.format("Multiple modules matching '%s:%s' in %s: %s", group, name, configuration, list));
        }

        return Optional.of(Iterables.getOnlyElement(list).getVersion());
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
