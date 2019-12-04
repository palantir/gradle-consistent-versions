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

import com.google.common.base.Functions;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.base.Suppliers;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Streams;
import com.palantir.gradle.versions.internal.MyModuleIdentifier;
import com.palantir.gradle.versions.internal.MyModuleVersionIdentifier;
import com.palantir.gradle.versions.lockstate.Dependents;
import com.palantir.gradle.versions.lockstate.FullLockState;
import com.palantir.gradle.versions.lockstate.Line;
import com.palantir.gradle.versions.lockstate.LockState;
import com.palantir.gradle.versions.lockstate.LockStates;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.inject.Inject;
import netflix.nebula.dependency.recommender.RecommendationStrategies;
import netflix.nebula.dependency.recommender.provider.RecommendationProviderContainer;
import org.gradle.api.GradleException;
import org.gradle.api.Named;
import org.gradle.api.NamedDomainObjectProvider;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.DependencyConstraint;
import org.gradle.api.artifacts.DependencySet;
import org.gradle.api.artifacts.ExternalModuleDependency;
import org.gradle.api.artifacts.ModuleDependency;
import org.gradle.api.artifacts.ModuleIdentifier;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.ProjectDependency;
import org.gradle.api.artifacts.ResolvableDependencies;
import org.gradle.api.artifacts.VersionConstraint;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ComponentSelector;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentSelector;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.artifacts.dsl.DependencyConstraintHandler;
import org.gradle.api.artifacts.result.ResolutionResult;
import org.gradle.api.artifacts.result.ResolvedComponentResult;
import org.gradle.api.artifacts.result.UnresolvedDependencyResult;
import org.gradle.api.attributes.Attribute;
import org.gradle.api.attributes.AttributesSchema;
import org.gradle.api.attributes.Usage;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.invocation.Gradle;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.logging.configuration.ShowStacktrace;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.publish.maven.MavenPublication;
import org.gradle.api.publish.maven.tasks.GenerateMavenPom;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.language.base.plugins.LifecycleBasePlugin;
import org.gradle.util.GUtil;
import org.gradle.util.GradleVersion;
import org.immutables.value.Value;

public class VersionsLockPlugin implements Plugin<Project> {
    private static final Logger log = Logging.getLogger(VersionsLockPlugin.class);
    private static final GradleVersion MINIMUM_GRADLE_VERSION = GradleVersion.version("5.1");

    /** Root project configuration that collects all the dependencies from each project. */
    static final String UNIFIED_CLASSPATH_CONFIGURATION_NAME = "unifiedClasspath";

    /** Per-project configuration that gets resolved when resolving the user's inter-project dependencies. */
    private static final String PLACEHOLDER_CONFIGURATION_NAME = "consistentVersionsPlaceholder";

    /** Configuration to which we apply the constraints from the lock file. */
    private static final String LOCK_CONSTRAINTS_CONFIGURATION_NAME = "lockConstraints";

    private static final String CONSISTENT_VERSIONS_PRODUCTION = "consistentVersionsProduction";
    private static final String CONSISTENT_VERSIONS_TEST = "consistentVersionsTest";
    private static final String VERSIONS_LOCK_EXTENSION = "versionsLock";

    private static final Attribute<GcvUsage> GCV_USAGE_ATTRIBUTE =
            Attribute.of("com.palantir.consistent-versions.usage", GcvUsage.class);

    public enum GcvUsage implements Named {
        /**
         * GCV is using configurations with this usage to source all dependencies from a given project. Only {@link
         * #PLACEHOLDER_CONFIGURATION_NAME} should have this usage.
         *
         * <p>This exists so that the build's normal inter-project dependencies will naturally resolve to that
         * configuration, without having to re-write
         */
        GCV_SOURCE;

        /** Must match the enum name exactly, so you can pass this into {@link #valueOf(String)}. */
        @Override
        public String getName() {
            return this.name();
        }
    }

    private static final Attribute<GcvScope> GCV_SCOPE_ATTRIBUTE =
            Attribute.of("com.palantir.consistent-versions.scope", GcvScope.class);

    public enum GcvScope implements Named {
        PRODUCTION,
        TEST;

        /** Must match the enum name exactly, so you can pass this into {@link #valueOf(String)}. */
        @Override
        public String getName() {
            return this.name();
        }
    }

    private static final Comparator<GcvScope> GCV_SCOPE_COMPARATOR = Comparator.comparing(scope -> {
        // Production takes priority over test when it comes to provenance.
        switch (scope) {
            case PRODUCTION:
                return 0;
            case TEST:
                return 1;
        }
        throw new RuntimeException("Unexpected GcvScope: " + scope);
    });

    private static final Attribute<String> GCV_SCOPE_RESOLUTION_ATTRIBUTE =
            Attribute.of("com.palantir.consistent-versions.scope", String.class);

    private final ShowStacktrace showStacktrace;

    /**
     * We don't want the consumable configurations we create ({@link #PLACEHOLDER_CONFIGURATION_NAME}, {@link
     * #CONSISTENT_VERSIONS_PRODUCTION}, {@link #CONSISTENT_VERSIONS_TEST}) and downstream collected {@link
     * #recursivelyCopyProjectDependencies(Project, DependencySet) configurations that we copy} to have any known usage,
     * so we give them this usage. This is so that:
     *
     * <ul>
     *   <li>they don't cause an ambiguity between the copied and the original {@code apiElements}, {@code
     *       runtimeElements} etc., when a resolution with a required usage is performed (such as by resolving a {@code
     *       compileClasspath} or {@code runtimeClasspath} configuration)
     *   <li>to avoid {@link #PLACEHOLDER_CONFIGURATION_NAME} being resolved as an actual candidate in normal
     *       resolution, when all other candidates didn't match, simply because it had completely distinct attributes
     *       from the requested attributes.
     * </ul>
     */
    private final Usage internalUsage;

    @Inject
    public VersionsLockPlugin(Gradle gradle, ObjectFactory objectFactory) {
        showStacktrace = gradle.getStartParameter().getShowStacktrace();
        internalUsage = objectFactory.named(Usage.class, "consistent-versions-usage");
    }

    static Path getRootLockFile(Project project) {
        return project.file("versions.lock").toPath();
    }

    @Override
    public final void apply(Project project) {
        checkPreconditions(project);
        project.getPluginManager().apply(LifecycleBasePlugin.class);

        project.allprojects(p -> {
            AttributesSchema attributesSchema = p.getDependencies().getAttributesSchema();
            attributesSchema.attribute(GCV_SCOPE_ATTRIBUTE);
            attributesSchema.attribute(GCV_USAGE_ATTRIBUTE);
        });

        Configuration unifiedClasspath = project.getConfigurations()
                .create(UNIFIED_CLASSPATH_CONFIGURATION_NAME, conf -> {
                    conf.setVisible(false).setCanBeConsumed(false);

                    // Attributes declared here will become required attributes when resolving this configuration
                    conf.getAttributes().attribute(GCV_USAGE_ATTRIBUTE, GcvUsage.GCV_SOURCE);
                });

        project.allprojects(subproject -> {
            subproject.getExtensions().create(VERSIONS_LOCK_EXTENSION, VersionsLockExtension.class, subproject);
            setupDependenciesToProject(project, unifiedClasspath, subproject);
        });

        Path rootLockfile = getRootLockFile(project);

        Supplier<FullLockState> fullLockStateSupplier = Suppliers.memoize(() -> {
            ResolutionResult resolutionResult = unifiedClasspath.getIncoming().getResolutionResult();
            // Throw if there are dependencies that are not present in the lock state.
            failIfAnyDependenciesUnresolved(resolutionResult);
            return computeLockState(resolutionResult);
        });

        // We apply 'java-base' because we need the JavaEcosystemVariantDerivationStrategy for platforms to work
        // (but that's internal)
        project.getPluginManager().apply("java-base");

        if (project.getGradle().getStartParameter().isWriteDependencyLocks()) {
            // If you only run ':subproject:resolveConfigurations --write-locks', gradle wouldn't resolve the root
            // unifiedClasspath configuration, which would behave differently than if we ran 'resolveConfigurations'.
            // Workaround is we always force the 'unifiedClasspath' to be resolved.

            // Note: we don't use project.getGradle().projectsEvaluated() as gradle warns us against resolving the
            // configuration at that point. See
            // https://docs.gradle.org/5.1/userguide/troubleshooting_dependency_resolution.html#sub:configuration_resolution_constraints
            project.afterEvaluate(p -> {
                p.getSubprojects().forEach(subproject -> p.evaluationDependsOn(subproject.getPath()));
                ResolvableDependencies incoming = unifiedClasspath.getIncoming();

                Map<Project, LockedConfigurations> lockedConfigurations = wireUpLockedConfigurationsByProject(project);
                recursivelyCopyProjectDependencies(project, incoming.getDependencies());
                // Triggers evaluation of unifiedClasspath
                new ConflictSafeLockFile(rootLockfile).writeLocks(fullLockStateSupplier.get());
                log.lifecycle("Finished writing lock state to {}", rootLockfile);
                configureAllProjectsUsingConstraints(project, rootLockfile, lockedConfigurations);
            });
        } else {
            // afterEvaluate is necessary to ensure all projects' dependencies have been configured, because we
            // need to copy them eagerly before we add the constraints from the lock file.

            // Note: do not use Gradle.projectsEvaluated here, as IntelliJ's [gradle import action][1] happens BEFORE
            // that, and will thus resolve the locked configurations before we get a change to add constraints to them.
            // [1]:
            // https://github.com/JetBrains/intellij-community/commit/f394c51cff59c69bbaf63a8bf67cefbad9e357aa#diff-04b9936e4249a0f5727414555b76c4b9R123
            project.afterEvaluate(g -> {
                // Ensure all other projects have been resolved before.
                // This is so that plugins that configure subprojects (gradle-conjure) continue to work.
                project.getSubprojects().forEach(subproject -> project.evaluationDependsOn(subproject.getPath()));

                // Recursively copy all project dependencies, so that the constraints we add below won't affect the
                // resolution of unifiedClasspath.
                Map<Project, LockedConfigurations> lockedConfigurations = wireUpLockedConfigurationsByProject(project);
                recursivelyCopyProjectDependencies(project, unifiedClasspath.getIncoming().getDependencies());

                if (isIgnoreLockFile(project)) {
                    log.lifecycle("Ignoring lock file for debugging, because the 'ignoreLockFile' property was set");
                    return;
                }

                if (Files.notExists(rootLockfile)) {
                    throw new GradleException(String.format(
                            "Root lock file '%s' doesn't exist, please run "
                                    + "`./gradlew --write-locks` to initialise locks",
                            rootLockfile));
                }

                configureAllProjectsUsingConstraints(project, rootLockfile, lockedConfigurations);
            });
        }

        TaskProvider verifyLocks = project.getTasks().register("verifyLocks", VerifyLocksTask.class, task -> {
            task.getCurrentLockState().set(project.provider(() -> LockStates.toLockState(fullLockStateSupplier.get())));
            task.getPersistedLockState()
                    .set(project.provider(() -> new ConflictSafeLockFile(rootLockfile).readLocks()));
        });
        project.getTasks().named(LifecycleBasePlugin.CHECK_TASK_NAME).configure(check -> check.dependsOn(verifyLocks));

        project.getTasks().register("why", WhyDependencyTask.class, t -> {
            t.lockfile(rootLockfile);
            t.fullLockState(project.provider(fullLockStateSupplier::get));
        });
    }

    static boolean isIgnoreLockFile(Project project) {
        return project.hasProperty("ignoreLockFile");
    }

    private static Map<Project, LockedConfigurations> wireUpLockedConfigurationsByProject(Project rootProject) {
        return rootProject.getAllprojects().stream().collect(Collectors.toMap(Functions.identity(), subproject -> {
            VersionsLockExtension ext = subproject.getExtensions().getByType(VersionsLockExtension.class);
            LockedConfigurations lockedConfigurations = computeConfigurationsToLock(subproject, ext);
            addConfigurationDependencies(
                    subproject,
                    subproject.getConfigurations().getByName(CONSISTENT_VERSIONS_PRODUCTION),
                    lockedConfigurations.productionConfigurations());
            addConfigurationDependencies(
                    subproject,
                    subproject.getConfigurations().getByName(CONSISTENT_VERSIONS_TEST),
                    lockedConfigurations.testConfigurations());
            return lockedConfigurations;
        }));
    }

    /**
     * This method sets up the necessary intermediate configurations in each project, and wires up the dependencies from
     * {@link #UNIFIED_CLASSPATH_CONFIGURATION_NAME} to these configurations. It doesn't wire up the actual
     * configurations that we intend to lock, because that will be done later, in afterEvaluate.
     */
    private void setupDependenciesToProject(Project rootProject, Configuration unifiedClasspath, Project project) {
        // Parallel 'resolveConfigurations' sometimes breaks unless we force the root one to run first.
        if (rootProject != project) {
            project.getPluginManager().withPlugin("com.palantir.configuration-resolver", plugin -> {
                project.getTasks().named("resolveConfigurations", task -> task.mustRunAfter(":resolveConfigurations"));
            });
        }

        // This is not how we collect dependencies, but is only meant to capture and neutralize the user's
        // inter-project dependencies.
        project.getConfigurations().register(PLACEHOLDER_CONFIGURATION_NAME, conf -> {
            conf.setVisible(false).setCanBeResolved(false);

            // Make sure it can never be selected as part of normal resolution that declares a required usage.
            conf.getAttributes().attribute(Usage.USAGE_ATTRIBUTE, internalUsage);

            // Mark it as a GCV_SOURCE, so that when we resolve {@link #UNIFIED_CLASSPATH_CONFIGURATION_NAME}
            // it becomes selected (as the best matching configuration) for the user's normal inter-project dependencies
            // instead of the two configurations below (or apiElements, runtimeElements etc)
            conf.getAttributes().attribute(GCV_USAGE_ATTRIBUTE, GcvUsage.GCV_SOURCE);
        });

        project.getConfigurations().register(CONSISTENT_VERSIONS_PRODUCTION, conf -> {
            conf.setDescription(
                    "Outgoing configuration for production dependencies meant to be used by " + "consistent-versions");
            conf.setVisible(false); // needn't be visible from other projects
            conf.setCanBeConsumed(true);
            conf.setCanBeResolved(false);
            conf.getAttributes().attribute(Usage.USAGE_ATTRIBUTE, internalUsage);
            conf.getOutgoing().capability(capabilityFor(project, GcvScope.PRODUCTION));
        });

        project.getConfigurations().register(CONSISTENT_VERSIONS_TEST, conf -> {
            conf.setDescription(
                    "Outgoing configuration for test dependencies meant to be used by " + "consistent-versions");
            conf.setVisible(false); // needn't be visible from other projects
            conf.setCanBeConsumed(true);
            conf.setCanBeResolved(false);
            conf.getAttributes().attribute(Usage.USAGE_ATTRIBUTE, internalUsage);
            conf.getOutgoing().capability(capabilityFor(project, GcvScope.TEST));
        });

        unifiedClasspath.getDependencies().add(createConfigurationDependencyWithScope(project, GcvScope.PRODUCTION));
        unifiedClasspath.getDependencies().add(createConfigurationDependencyWithScope(project, GcvScope.TEST));
    }

    private static String capabilityFor(Project project, GcvScope scope) {
        return String.format("gcv:%s-%s-%s:0", project.getGroup().toString(), project.getName(), scope.getName());
    }

    /**
     * {@code fromConf} must be eager, as adding a dependency here will trigger other code to run in {@link
     * #recursivelyCopyProjectDependenciesWithScope}.
     */
    private static void addConfigurationDependencies(
            Project project, Configuration fromConf, Set<Configuration> toConfs) {
        toConfs.forEach(toConf -> fromConf.getDependencies().add(createConfigurationDependency(project, toConf)));
    }

    /** Create a dependency to {@code toConfiguration}, where the latter should exist in the given {@code project}. */
    private static ProjectDependency createConfigurationDependency(Project project, Configuration toConfiguration) {
        return (ProjectDependency) project.getDependencies().project(ImmutableMap.of(
                "path", project.getPath(), "configuration", toConfiguration.getName()));
    }

    /** Create a dependency to {@code toConfiguration}, where the latter should exist in the given {@code project}. */
    private static Dependency createConfigurationDependencyWithScope(Project project, GcvScope scope) {
        ProjectDependency projectDependency = (ProjectDependency) project.getDependencies().create(project);
        projectDependency.capabilities(moduleDependencyCapabilitiesHandler -> {
            moduleDependencyCapabilitiesHandler.requireCapabilities(capabilityFor(project, scope));
        });
        ModuleDependency dep =
                GradleWorkarounds.fixAttributesOfModuleDependency(project.getObjects(), projectDependency);
        dep.attributes(attr -> attr.attribute(GCV_SCOPE_ATTRIBUTE, scope));
        return dep;
    }

    private static void checkPreconditions(Project project) {
        Preconditions.checkState(
                GradleVersion.current().compareTo(MINIMUM_GRADLE_VERSION) >= 0,
                "This plugin requires gradle >= %s",
                MINIMUM_GRADLE_VERSION);

        if (!project.getRootProject().equals(project)) {
            throw new GradleException("Must be applied only to root project");
        }

        Preconditions.checkState(
                !project.getGradle().getStartParameter().isConfigureOnDemand(),
                "Gradle Consistent Versions doesn't currently work with configure-on-demand, please remove"
                        + " 'org.gradle.configureondemand' from your gradle.properties");

        Multimap<String, Project> coordinateDuplicates = LinkedHashMultimap.create();
        Set<Project> subprojectsLeft = new HashSet<>(project.getSubprojects());
        project.subprojects(subproject -> {
            subproject.afterEvaluate(sub -> {
                if (haveSameGroupAndName(project, sub)) {
                    throw new GradleException(String.format(
                            "This plugin doesn't work if the root project shares both group and name with a"
                                    + " subproject. Consider adding the following to settings.gradle:\n"
                                    + "rootProject.name = '%s-root'",
                            project.getName()));
                }
                String coordinate = String.format("%s:%s", subproject.getGroup(), subproject.getName());
                coordinateDuplicates.put(coordinate, subproject);

                // Finally, check if there were any duplicates.
                subprojectsLeft.remove(subproject);
                if (subprojectsLeft.isEmpty()) {
                    checkForDuplicatesInSubprojects(coordinateDuplicates);
                }
            });
        });

        project.subprojects(subproject -> {
            subproject.afterEvaluate(sub -> {
                sub.getPluginManager().withPlugin("nebula.dependency-recommender", plugin -> {
                    RecommendationProviderContainer container =
                            sub.getExtensions().findByType(RecommendationProviderContainer.class);
                    if (container.getStrategy() == RecommendationStrategies.OverrideTransitives) {
                        throw new GradleException("Must not use strategy OverrideTransitives for "
                                + sub
                                + ". "
                                + "Use this instead: dependencyRecommendations { strategy ConflictResolved }");
                    }
                });
            });
        });
    }

    private static void checkForDuplicatesInSubprojects(Multimap<String, Project> coordinateDuplicates) {
        Map<String, Collection<Project>> duplicates =
                ImmutableMap.copyOf(Maps.filterValues(coordinateDuplicates.asMap(), projects -> projects.size() > 1));

        if (!duplicates.isEmpty()) {
            throw new GradleException(String.format(
                    "All subprojects must have unique $group:$name coordinates, but found duplicates:\n%s",
                    duplicates.entrySet().stream()
                            .map(entry -> String.format(
                                    "- '%s' -> %s",
                                    entry.getKey(), Collections2.transform(entry.getValue(), Project::getPath)))
                            .collect(Collectors.joining("\n"))));
        }
    }

    private static void ensureNoFailOnVersionConflict(Configuration conf) {
        if (GradleWorkarounds.isFailOnVersionConflict(conf)) {
            throw new GradleException("Must not use failOnVersionConflict() for " + conf);
        }
    }

    /**
     * Gradle will break if you try to add constraints to any configurations that have been resolved. Since
     * unifiedClasspath depends on the SUBPROJECT_UNIFIED_CONFIGURATION_NAME configuration of all subprojects (above),
     * that would resolve them when we resolve unifiedClasspath. We need this workaround to enable the workflow:
     *
     * <ol>
     *   <li>when 'unifiedClasspath' is resolved with --write-locks, it writes the lock file and resolves its
     *       dependencies
     *   <li>read the lock file
     *   <li>enforce these versions on all subprojects, using constraints
     * </ol>
     *
     * <p>Since we can't apply these constraints to the already resolved configurations, we need a workaround to ensure
     * that unifiedClasspath does not directly depend on subproject configurations that we intend to enforce constraints
     * on.
     */
    private void recursivelyCopyProjectDependencies(Project project, DependencySet depSet) {
        Preconditions.checkState(
                project.getState().getExecuted(),
                "recursivelyCopyProjectDependenciesWithScope should be called in afterEvaluate");

        Map<Configuration, String> copiedConfigurationsCache = new HashMap<>();

        findProjectDependencyWithTargetConfigurationName(depSet, CONSISTENT_VERSIONS_PRODUCTION).forEach(conf ->
                recursivelyCopyProjectDependenciesWithScope(
                        project, conf.getDependencies(), copiedConfigurationsCache, GcvScope.PRODUCTION));

        findProjectDependencyWithTargetConfigurationName(depSet, CONSISTENT_VERSIONS_TEST).forEach(conf ->
                recursivelyCopyProjectDependenciesWithScope(
                        project, conf.getDependencies(), copiedConfigurationsCache, GcvScope.TEST));
    }

    private static List<Configuration> findProjectDependencyWithTargetConfigurationName(
            DependencySet depSet, String configurationName) {
        return depSet.stream()
                .filter(dep -> dep instanceof ProjectDependency)
                .map(dependency -> {
                    ProjectDependency projectDependency = (ProjectDependency) dependency;
                    return findConfigurationUsingCapabilities(projectDependency);
                })
                .filter(conf -> conf.getName().equals(configurationName))
                .collect(Collectors.toList());
    }

    /**
     * Recursive method that copies unseen {@link ProjectDependency project dependencies} found in the given {@link
     * DependencySet}, and then amends their {@link ProjectDependency#getTargetConfiguration()} to point to the copied
     * configuration. It then eagerly configures any copied Configurations recursively.
     */
    private void recursivelyCopyProjectDependenciesWithScope(
            Project currentProject,
            DependencySet dependencySet,
            Map<Configuration, String> copiedConfigurationsCache,
            GcvScope scope) {
        dependencySet.withType(ProjectDependency.class).all(projectDependency -> {
            Project projectDep = projectDependency.getDependencyProject();

            String targetConfiguration = projectDependency.getTargetConfiguration();
            if (targetConfiguration == null) {
                // Not handling variant-based selection in this code path
                return;
            }

            Configuration targetConf = getTargetConfiguration(dependencySet, projectDependency);

            log.info(
                    "Found legacy project dependency (with target configuration): {} -> {}",
                    dependencySet,
                    formatProjectDependency(projectDependency));

            if (copiedConfigurationsCache.containsKey(targetConf)) {
                String copiedConf = copiedConfigurationsCache.get(targetConf);
                log.debug(
                        "Re-using already copied target configuration for dep {} -> {}: {}",
                        currentProject,
                        targetConf,
                        copiedConf);
                projectDependency.setTargetConfiguration(copiedConf);
                return;
            }

            // Necessary to run withDependency actions run on the targetConf, because VersionsPropsPlugin
            // configures its constraints that way.
            // Without this, they'd just be propagated to the copiedConf and probably never run!
            causeWithDependenciesActionsToRun(targetConf);

            Configuration copiedConf = targetConf.copyRecursive();
            copiedConf.setDescription(String.format(
                    "Copy of the '%s' configuration that can be resolved by "
                            + "com.palantir.consistent-versions without resolving the '%s' configuration itself.",
                    targetConf.getName(), targetConf.getName()));

            // Update state about what we've seen
            copiedConfigurationsCache.put(targetConf, copiedConf.getName());

            if (log.isInfoEnabled()) {
                log.info(
                        "Recursively copied {}'s '{}' configuration, which has\n"
                                + " - dependencies: {}\n"
                                + " - constraints: {}",
                        projectDep,
                        targetConfiguration,
                        ImmutableList.copyOf(copiedConf.getAllDependencies()),
                        ImmutableList.copyOf(copiedConf.getAllDependencyConstraints()));
            }

            copiedConf.getAttributes().attribute(Usage.USAGE_ATTRIBUTE, internalUsage);
            // Must set this because we depend on this configuration when resolving unifiedClasspath.
            copiedConf.setCanBeConsumed(true);
            // But this should never be resolved! (it will most likely fail to given the usage above)
            copiedConf.setCanBeResolved(false);
            // Since we only depend on these from the same project (via CONSISTENT_VERSIONS_PRODUCTION or
            // CONSISTENT_VERSIONS_TEST), we shouldn't allow them to be visible outside this project.
            copiedConf.setVisible(false);
            // This is so we can get back the scope from the ResolutionResult.
            copiedConf.getDependencies().withType(ExternalModuleDependency.class).all(externalDep -> {
                GradleWorkarounds.fixAttributesOfModuleDependency(projectDep.getObjects(), externalDep);
                externalDep.attributes(attr -> attr.attribute(GCV_SCOPE_ATTRIBUTE, scope));
            });
            // To avoid capability based conflict detection between all these copied configurations, we give each
            // of them a totally random capability
            copiedConf.getOutgoing().capability(String.format("gcv:%s:0", UUID.randomUUID().toString()));

            projectDep.getConfigurations().add(copiedConf);

            projectDependency.setTargetConfiguration(copiedConf.getName());

            recursivelyCopyProjectDependenciesWithScope(
                    projectDep, copiedConf.getDependencies(), copiedConfigurationsCache, scope);
        });
    }

    /**
     * This causes {@link Configuration#withDependencies} actions to be run eagerly.
     *
     * <p>It's a hack but necessary to ensure that these actions run before copying said configuration.
     */
    private static void causeWithDependenciesActionsToRun(Configuration conf) {
        conf.getIncoming().getDependencies();
    }

    private static Configuration getTargetConfiguration(DependencySet depSet, ProjectDependency projectDependency) {
        String targetConfiguration = Preconditions.checkNotNull(
                projectDependency.getTargetConfiguration(),
                "Expected dependency to have a targetConfiguration: %s",
                formatProjectDependency(projectDependency));
        Configuration targetConf =
                projectDependency.getDependencyProject().getConfigurations().getByName(targetConfiguration);
        Preconditions.checkNotNull(
                targetConf,
                "Target configuration of project dependency was null: %s -> %s",
                depSet,
                projectDependency.getDependencyProject());
        return targetConf;
    }

    private static Configuration findConfigurationUsingCapabilities(ProjectDependency projectDependency) {
        Set<Configuration> confs = projectDependency.getDependencyProject().getConfigurations().stream()
                .filter(conf ->
                        conf.getOutgoing().getCapabilities().containsAll(projectDependency.getRequestedCapabilities()))
                .collect(Collectors.toSet());

        Preconditions.checkArgument(
                confs.size() == 1,
                "Expected to only find one target configuration but found %s with names: %s",
                confs.size(),
                confs);

        return Iterables.getOnlyElement(confs);
    }

    private static String formatProjectDependency(ProjectDependency dep) {
        StringBuilder builder = new StringBuilder();
        builder.append(dep.getDependencyProject());
        if (dep.getTargetConfiguration() != null) {
            builder.append(" (configuration: ");
            builder.append(dep.getTargetConfiguration());
            builder.append(")");
        }
        if (!dep.getAttributes().isEmpty()) {
            builder.append(", attributes: ");
            builder.append(dep.getAttributes().toString());
        }
        return builder.toString();
    }

    private static boolean haveSameGroupAndName(Project project, Project subproject) {
        return project.getName().equals(subproject.getName()) && project.getGroup().equals(subproject.getGroup());
    }

    private void failIfAnyDependenciesUnresolved(ResolutionResult resolutionResult) {
        List<UnresolvedDependencyResult> unresolved = resolutionResult.getAllDependencies().stream()
                .filter(a -> a instanceof UnresolvedDependencyResult)
                .map(a -> (UnresolvedDependencyResult) a)
                .collect(Collectors.toList());
        if (!unresolved.isEmpty()) {
            throw new GradleException(String.format(
                    "Could not compute lock state from configuration '%s' due to unresolved dependencies:\n%s",
                    UNIFIED_CLASSPATH_CONFIGURATION_NAME,
                    unresolved.stream().map(this::formatUnresolvedDependencyResult).collect(Collectors.joining("\n"))));
        }
    }

    /**
     * Assumes the resolution result succeeded, that is, {@link #failIfAnyDependenciesUnresolved} was run and didn't
     * throw.
     */
    private static FullLockState computeLockState(ResolutionResult resolutionResult) {
        Map<ResolvedComponentResult, GcvScope> scopeCache = new HashMap<>();

        FullLockState.Builder builder = FullLockState.builder();
        resolutionResult.getAllComponents().stream()
                .filter(component -> component.getId() instanceof ModuleComponentIdentifier)
                .forEach(component -> {
                    GcvScope scope = getScopeRecursively(component, scopeCache);
                    switch (scope) {
                        case PRODUCTION:
                            builder.putProductionDeps(
                                    MyModuleVersionIdentifier.copyOf(component.getModuleVersion()),
                                    extractDependents(component));
                            return;
                        case TEST:
                            builder.putTestDeps(
                                    MyModuleVersionIdentifier.copyOf(component.getModuleVersion()),
                                    extractDependents(component));
                            return;
                    }
                    throw new RuntimeException(String.format(
                            "Unexpected scope for component %s: %s", component.getModuleVersion(), scope));
                });
        return builder.build();
    }

    private static GcvScope getScopeRecursively(
            ResolvedComponentResult component, Map<ResolvedComponentResult, GcvScope> scopeCache) {
        Optional<GcvScope> cached = Optional.ofNullable(scopeCache.get(component));
        if (cached.isPresent()) {
            return cached.get();
        }

        GcvScope gcvScope = component.getDependents().stream()
                .filter(dep -> !dep.isConstraint())
                .flatMap(dependent -> {
                    ComponentIdentifier id = dependent.getFrom().getId();
                    if (id instanceof ProjectComponentIdentifier) {
                        String maybeScope =
                                dependent.getRequested().getAttributes().getAttribute(GCV_SCOPE_RESOLUTION_ATTRIBUTE);
                        return Streams.stream(Optional.ofNullable(maybeScope).map(GcvScope::valueOf));
                    }
                    return Stream.of(getScopeRecursively(dependent.getFrom(), scopeCache));
                })
                .min(GCV_SCOPE_COMPARATOR)
                .orElseThrow(() -> new RuntimeException("Couldn't determine scope for dependency: " + component));

        scopeCache.put(component, gcvScope);
        return gcvScope;
    }

    private static Dependents extractDependents(ResolvedComponentResult component) {
        return Dependents.of(component.getDependents().stream().collect(Collectors.groupingBy(
                dep -> dep.getFrom().getId(),
                () -> new TreeMap<>(GradleComparators.COMPONENT_IDENTIFIER_COMPARATOR),
                Collectors.mapping(dep -> getRequestedVersionConstraint(dep.getRequested()), Collectors.toCollection(
                        () -> new TreeSet<>(Comparator.comparing(VersionConstraint::toString)))))));
    }

    private static VersionConstraint getRequestedVersionConstraint(ComponentSelector requested) {
        if (requested instanceof ModuleComponentSelector) {
            return ((ModuleComponentSelector) requested).getVersionConstraint();
        }
        throw new RuntimeException(String.format(
                "Expecting a ModuleComponentSelector but found a %s: %s", requested.getClass(), requested));
    }

    /**
     * Essentially replicates what {@link
     * org.gradle.api.tasks.diagnostics.internal.insight.DependencyInsightReporter#collectErrorMessages} does, since
     * that whole class is not public API.
     */
    private String formatUnresolvedDependencyResult(UnresolvedDependencyResult result) {
        StringBuilder failures = new StringBuilder();
        for (Throwable failure = result.getFailure(); failure != null; failure = failure.getCause()) {
            failures.append("         - ");
            failures.append(failure.getMessage());
            if (showStacktrace == ShowStacktrace.ALWAYS_FULL) {
                failures.append("\n");
                StringWriter out = new StringWriter();
                failure.printStackTrace(new PrintWriter(out));
                Streams.stream(Splitter.on('\n').split(out.getBuffer()))
                        .map(line -> "           " + line + "\n")
                        .forEachOrdered(failures::append);
            }
            failures.append("\n");
        }
        return String.format(
                " * %s (requested: '%s' because: %s)\n      Failures:\n%s",
                result.getAttempted(), result.getRequested(), result.getAttemptedReason(), failures);
    }

    private static void configureAllProjectsUsingConstraints(
            Project rootProject, Path gradleLockfile, Map<Project, LockedConfigurations> lockedConfigurations) {
        List<DependencyConstraint> strictConstraints =
                constructConstraintsFromLockFile(gradleLockfile, rootProject.getDependencies().getConstraints());
        List<DependencyConstraint> publishableConstraints = constructPublishableConstraintsFromLockFile(
                gradleLockfile, rootProject.getDependencies().getConstraints());
        rootProject.allprojects(subproject ->
                configureUsingConstraints(
                        subproject, strictConstraints, publishableConstraints, lockedConfigurations.get(subproject)));
    }

    private static void configureUsingConstraints(
            Project subproject,
            List<DependencyConstraint> lockConstraints,
            List<DependencyConstraint> publishableConstraints,
            LockedConfigurations lockedConfigurations) {
        Configuration locksConfiguration = subproject
                .getConfigurations()
                .create(LOCK_CONSTRAINTS_CONFIGURATION_NAME, locksConf -> {
                    locksConf.setVisible(false);
                    locksConf.setCanBeConsumed(false);
                    locksConf.setCanBeResolved(false);
                    lockConstraints.forEach(locksConf.getDependencyConstraints()::add);
                });

        Set<Configuration> configurationsToLock = lockedConfigurations.allConfigurations();
        log.info("Configuring locks for {}. Locked configurations: {}", subproject.getPath(), configurationsToLock);
        configurationsToLock.forEach(conf -> {
            conf.extendsFrom(locksConfiguration);
            VersionsLockPlugin.ensureNoFailOnVersionConflict(conf);
        });

        // Ok, now configure the published configurations.
        subproject.getPluginManager().withPlugin("java", plugin -> {
            configurePublishConstraints(
                    subproject,
                    subproject.getConfigurations().named(JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME),
                    subproject.getConfigurations().named(JavaPlugin.API_ELEMENTS_CONFIGURATION_NAME),
                    publishableConstraints);
            configurePublishConstraints(
                    subproject,
                    subproject.getConfigurations().named(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME),
                    subproject.getConfigurations().named(JavaPlugin.RUNTIME_ELEMENTS_CONFIGURATION_NAME),
                    publishableConstraints);
        });
    }

    /**
     * Configures constraints on the given {@code configuration}, but only on the dependencies that show up in the
     * resolution result of {@code configurationForFiltering}.
     *
     * <p>We can assume that the {@code java} plugin has been applied on the project.
     */
    private static void configurePublishConstraints(
            Project project,
            NamedDomainObjectProvider<Configuration> configurationForFiltering,
            NamedDomainObjectProvider<Configuration> configuration,
            List<DependencyConstraint> publishConstraints) {
        ListProperty<DependencyConstraint> constraintsProperty =
                GradleWorkarounds.fixListProperty(project.getObjects().listProperty(DependencyConstraint.class));
        constraintsProperty.addAll(project.provider(Suppliers.memoize(() -> {
            log.debug(
                    "Computing publish constraints for {} by resolving {}",
                    configuration.get(),
                    configurationForFiltering.get());
            Set<ModuleIdentifier> modulesToInclude =
                    configurationForFiltering.get().getIncoming().getResolutionResult().getAllComponents().stream()
                            .map(ResolvedComponentResult::getModuleVersion)
                            .filter(Objects::nonNull)
                            .map(ModuleVersionIdentifier::getModule)
                            .collect(Collectors.toSet());
            return Collections2.filter(
                    publishConstraints, constraint -> modulesToInclude.contains(constraint.getModule()));
        })::get));
        configuration.configure(conf -> {
            conf.getDependencyConstraints().addAllLater(constraintsProperty);

            // Make it obvious to gradle that "building" this configuration depends on configurationForFiltering
            ConfigurableFileCollection fileCollection = project.files().builtBy(configurationForFiltering);
            conf.getDependencies().add(project.getDependencies().create(fileCollection));

            // Make it obvious to gradle that generating a pom file for java publications requires resolving the
            // configurationForFiltering.
            // We'd like to figure out which publications depend on the `jar` task, and configure just those,
            // but I don't know how to do that without triggering a resolve of the configurationForFiltering,
            // which can transitively "lock" other publishable configurations by walking through its project
            // dependencies, thereby breaking the 'addAllLater' call above for other projects.
            project.getPluginManager().withPlugin("maven-publish", plugin ->
                    project.getExtensions()
                            .getByType(PublishingExtension.class)
                            .getPublications()
                            .withType(MavenPublication.class)
                            .all(publication -> {
                                log.info(
                                        "Configuring publication {} of project {}",
                                        publication.getName(),
                                        project.getPath());
                                String publicationName = publication.getName();
                                String publishTaskName =
                                        GUtil.toLowerCamelCase("generatePomFileFor " + publicationName + "Publication");
                                project.getTasks()
                                        .withType(GenerateMavenPom.class)
                                        .named(publishTaskName)
                                        .configure(task -> task.dependsOn(configurationForFiltering));
                            }));
        });
    }

    private static LockedConfigurations computeConfigurationsToLock(Project project, VersionsLockExtension ext) {
        Preconditions.checkState(
                project.getState().getExecuted(),
                "computeConfigurationsToLock should be called in afterEvaluate: %s",
                project);

        ImmutableLockedConfigurations.Builder lockedConfigurations = ImmutableLockedConfigurations.builder();

        lockedConfigurations.addAllProductionConfigurations(
                Collections2.transform(ext.getProductionConfigurations(), project.getConfigurations()::getByName));
        lockedConfigurations.addAllTestConfigurations(
                Collections2.transform(ext.getTestConfigurations(), project.getConfigurations()::getByName));

        if (ext.isUseJavaPluginDefaults() && project.getPluginManager().hasPlugin("java")) {
            SourceSetContainer sourceSets =
                    project.getConvention().getPlugin(JavaPluginConvention.class).getSourceSets();

            lockedConfigurations.addAllProductionConfigurations(
                    getConfigurationsForSourceSet(project, sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME)));

            // Use heuristic for test source sets.
            sourceSets.matching(sourceSet -> sourceSet.getName().toLowerCase().endsWith("test")).forEach(sourceSet ->
                    lockedConfigurations.addAllTestConfigurations(getConfigurationsForSourceSet(project, sourceSet)));
        }
        ImmutableLockedConfigurations result = lockedConfigurations.build();
        log.info("Computed locked configurations for {}: {}", project, result);

        // Prevent user trying to lock any configuration that could get published, such as runtimeElements,
        // apiElements etc. (Their constraints get published so we don't want to start publishing strictly locked
        // constraints)
        result.allConfigurations().forEach(conf -> {
            Preconditions.checkArgument(
                    !conf.isCanBeConsumed() && conf.isCanBeResolved(),
                    "May only lock 'sink' configurations that are resolvable and not consumable: %s",
                    conf);
        });

        return result;
    }

    private static ImmutableSet<Configuration> getConfigurationsForSourceSet(Project project, SourceSet sourceSet) {
        return ImmutableSet.of(
                project.getConfigurations().getByName(sourceSet.getCompileClasspathConfigurationName()),
                project.getConfigurations().getByName(sourceSet.getRuntimeClasspathConfigurationName()));
    }

    /** The final set of configurations that will be locked for a given project. */
    @Value.Immutable
    interface LockedConfigurations {
        Set<Configuration> productionConfigurations();

        Set<Configuration> testConfigurations();

        @Value.Auxiliary
        default ImmutableSet<Configuration> allConfigurations() {
            return ImmutableSet.copyOf(Iterables.concat(productionConfigurations(), testConfigurations()));
        }
    }

    private static List<DependencyConstraint> constructConstraintsFromLockFile(
            Path gradleLockfile, DependencyConstraintHandler constraintHandler) {
        LockState lockState = new ConflictSafeLockFile(gradleLockfile).readLocks();
        Stream<Entry<MyModuleIdentifier, Line>> locks = Stream.concat(
                lockState.productionLinesByModuleIdentifier().entrySet().stream(),
                lockState.testLinesByModuleIdentifier().entrySet().stream());
        return locks.map(e -> e.getKey() + ":" + e.getValue().version())
                // Note: constraints.create sets the version as preferred + required, we want 'strictly' just like
                // gradle does when verifying a lock file.
                .map(notation -> constraintHandler.create(notation, constraint -> {
                    constraint.version(v -> {
                        String version = Objects.requireNonNull(constraint.getVersion());
                        v.strictly(version);
                    });
                    constraint.because("Locked by versions.lock");
                }))
                .collect(Collectors.toList());
    }

    private static List<DependencyConstraint> constructPublishableConstraintsFromLockFile(
            Path gradleLockfile, DependencyConstraintHandler constraintHandler) {
        LockState lockState = new ConflictSafeLockFile(gradleLockfile).readLocks();
        // We only publish the production locks.
        return lockState.productionLinesByModuleIdentifier().entrySet().stream()
                .map(e -> e.getKey() + ":" + e.getValue().version())
                .map(notation -> constraintHandler.create(notation, constraint -> {
                    constraint.version(v -> {
                        String version = Objects.requireNonNull(constraint.getVersion());
                        v.require(version);
                    });
                    constraint.because("Computed from com.palantir.consistent-versions' versions.lock");
                }))
                .collect(Collectors.toList());
    }
}
