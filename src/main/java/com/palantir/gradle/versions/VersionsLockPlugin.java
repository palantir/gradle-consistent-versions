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
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Streams;
import com.palantir.gradle.versions.internal.MyModuleVersionIdentifier;
import com.palantir.gradle.versions.lockstate.Dependents;
import com.palantir.gradle.versions.lockstate.FullLockState;
import com.palantir.gradle.versions.lockstate.LockStates;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
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
import org.gradle.api.artifacts.ProjectDependency;
import org.gradle.api.artifacts.ResolvableDependencies;
import org.gradle.api.artifacts.VersionConstraint;
import org.gradle.api.artifacts.component.ComponentSelector;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentSelector;
import org.gradle.api.artifacts.dsl.DependencyConstraintHandler;
import org.gradle.api.artifacts.result.ResolutionResult;
import org.gradle.api.artifacts.result.ResolvedComponentResult;
import org.gradle.api.artifacts.result.UnresolvedDependencyResult;
import org.gradle.api.attributes.Attribute;
import org.gradle.api.attributes.AttributeCompatibilityRule;
import org.gradle.api.attributes.AttributeMatchingStrategy;
import org.gradle.api.attributes.AttributesSchema;
import org.gradle.api.attributes.CompatibilityCheckDetails;
import org.gradle.api.invocation.Gradle;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.logging.configuration.ShowStacktrace;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.language.base.plugins.LifecycleBasePlugin;
import org.gradle.util.GradleVersion;
import org.jetbrains.annotations.NotNull;

public class VersionsLockPlugin implements Plugin<Project> {
    private static final Logger log = Logging.getLogger(VersionsLockPlugin.class);
    private static final GradleVersion MINIMUM_GRADLE_VERSION = GradleVersion.version("5.1");

    /**
     * Root project configuration that collects all the dependencies from the
     * {@link #SUBPROJECT_UNIFIED_CONFIGURATION_NAME} of each project.
     */
    static final String UNIFIED_CLASSPATH_CONFIGURATION_NAME = "unifiedClasspath";
    /**
     * Per-project configuration that extends the configurations whose dependencies we are interested in.
     */
    private static final String SUBPROJECT_UNIFIED_CONFIGURATION_NAME = "subprojectUnifiedClasspath";
    /** Configuration to which we apply the constraints from the lock file. */
    private static final String LOCK_CONSTRAINTS_CONFIGURATION_NAME = "lockConstraints";
    private static final Attribute<Boolean> CONSISTENT_VERSIONS_CONSTRAINT_ATTRIBUTE =
            Attribute.of("consistent-versions", Boolean.class);

    public enum GcvUsage implements Named {
        /**
         * GCV is using configurations with this usage to source all dependencies from a given project.
         * Only {@link #SUBPROJECT_UNIFIED_CONFIGURATION_NAME} should have this usage.
         * <p>
         * This exists so that the build's normal inter-project dependencies will naturally resolve to that
         * configuration, without having to re-write
         */
        GCV_SOURCE,
        /**
         * Meant for aggregated configurations / copies of user-defined configurations, that GCV has made resolvable
         * for internal usage, but they are not meant to be discovered by user dependencies.
         */
        GCV_INTERNAL,
        /**
         * Any other configuration that the user may resolve / depend on.
         */
        ORIGINAL,
        ;

        @Override
        public String getName() {
            return this.name().toLowerCase();
        }
    }

    private static final Attribute<GcvUsage> GCV_USAGE_ATTRIBUTE =
            Attribute.of("com.palantir.consistent-versions.usage", GcvUsage.class);

    private final ShowStacktrace showStacktrace;

    @Inject
    public VersionsLockPlugin(Gradle gradle) {
        showStacktrace = gradle.getStartParameter().getShowStacktrace();
    }

    @NotNull
    static Path getRootLockFile(Project project) {
        return project.file("versions.lock").toPath();
    }

    @Override
    public final void apply(Project project) {
        checkPreconditions(project);
        project.getPluginManager().apply(LifecycleBasePlugin.class);

        project.allprojects(p -> {
            // Create the attribute
            p.getDependencies().getAttributesSchema().attribute(GCV_USAGE_ATTRIBUTE);
        });

        Configuration unifiedClasspath = project
                .getConfigurations()
                .create(UNIFIED_CLASSPATH_CONFIGURATION_NAME, conf -> {
                    conf.setVisible(false).setCanBeConsumed(false);
                    // Mark it as accepting dependencies with our own usage
                    conf.getAttributes().attribute(GCV_USAGE_ATTRIBUTE, GcvUsage.GCV_SOURCE);
                });

        project.allprojects(p -> {
            AttributesSchema attributesSchema = p.getDependencies().getAttributesSchema();
            AttributeMatchingStrategy<GcvUsage> matchingStrategy = attributesSchema.attribute(GCV_USAGE_ATTRIBUTE);
            matchingStrategy.getCompatibilityRules().add(ConsistentVersionsCompatibilityRules.class);
        });

        project.allprojects(subproject -> {
            sourceDependenciesFromProject(project, unifiedClasspath, subproject);
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
        // TODO(dsanduleac): we will move to java-platform in the future (probably once 5.2 is out)
        // See: https://github.com/gradle/gradle/pull/7967
        project.getPluginManager().apply("java-base");

        if (project.getGradle().getStartParameter().isWriteDependencyLocks()) {
            // Must wire up the constraint configuration to right AFTER rootProject has written theirs
            unifiedClasspath.getIncoming().afterResolve(r -> {
                new ConflictSafeLockFile(rootLockfile).writeLocks(fullLockStateSupplier.get());
                log.lifecycle("Finished writing lock state to {}", rootLockfile);
                configureAllProjectsUsingConstraints(project, rootLockfile);
            });

            // If you only run ':subproject:resolveConfigurations --write-locks', gradle wouldn't resolve the root
            // unifiedClasspath configuration, which would behave differently than if we ran 'resolveConfigurations'.
            // Workaround is we always force the 'unifiedClasspath' to be resolved.

            // Note: we don't use project.getGradle().projectsEvaluated() as gradle warns us against resolving the
            // configuration at that point. See https://docs.gradle.org/5.1/userguide/troubleshooting_dependency_resolution.html#sub:configuration_resolution_constraints
            project.afterEvaluate(p -> {
                p.evaluationDependsOnChildren();
                ResolvableDependencies incoming = unifiedClasspath.getIncoming();
                recursivelyCopyProjectDependencies(project, incoming.getDependencies());
                incoming.getResolutionResult().getRoot();
            });
        } else {
            if (project.hasProperty("ignoreLockFile")) {
                log.lifecycle("Ignoring lock file for debugging, because the 'ignoreLockFile' property was set");
                return;
            }

            if (Files.notExists(rootLockfile)) {
                throw new GradleException(String.format("Root lock file '%s' doesn't exist, please run "
                        + "`./gradlew --write-locks` to initialise locks", rootLockfile));
            }

            // projectsEvaluated is necessary to ensure all projects' dependencies have been configured, because we
            // need to copy them eagerly before we add the constraints from the lock file.
            project.getGradle().projectsEvaluated(g -> {
                // Recursively copy all project dependencies, so that the constraints we add below won't affect the
                // resolution of unifiedClasspath.
                recursivelyCopyProjectDependencies(project, unifiedClasspath.getIncoming().getDependencies());

                configureAllProjectsUsingConstraints(project, rootLockfile);
            });

            TaskProvider verifyLocks = project.getTasks().register("verifyLocks", VerifyLocksTask.class, task -> {
                task.getCurrentLockState()
                        .set(project.provider(() -> LockStates.toLockState(fullLockStateSupplier.get())));
                task.getPersistedLockState()
                        .set(project.provider(() -> new ConflictSafeLockFile(rootLockfile).readLocks()));
            });
            project.getTasks().named(LifecycleBasePlugin.CHECK_TASK_NAME).configure(check -> {
                check.dependsOn(verifyLocks);
            });

            project.getTasks().register("why", WhyDependencyTask.class, t -> {
                t.lockfile(rootLockfile);
                t.fullLockState(project.provider(fullLockStateSupplier::get));
            });
        }
    }

    private static void sourceDependenciesFromProject(
            Project rootProject, Configuration unifiedClasspath, Project project) {
        // Parallel 'resolveConfigurations' sometimes breaks unless we force the root one to run first.
        if (rootProject != project) {
            project.getPluginManager().withPlugin("com.palantir.configuration-resolver", plugin -> {
                project.getTasks().named("resolveConfigurations", task -> task.mustRunAfter(":resolveConfigurations"));
            });
        }

        project.getConfigurations().register(SUBPROJECT_UNIFIED_CONFIGURATION_NAME, conf -> {
            conf.setVisible(false).setCanBeResolved(false);

            // Mark it so it doesn't receive constraints from VersionsPropsPlugin
            conf.getAttributes().attribute(VersionsPropsPlugin.CONFIGURATION_EXCLUDE_ATTRIBUTE, true);

            // Mark it as a GCV_SOURCE, so that
            // 1. it can be depended on when resolving `unifiedClasspath`, which requests that the usage is GCV_SOURCE
            // 2. it becomes selected (as the best matching configuration) for the user's normal inter-project
            //    dependencies
            conf.getAttributes().attribute(GCV_USAGE_ATTRIBUTE, GcvUsage.GCV_SOURCE);
        });
        // Depend on this "sink" configuration from our global aggregating configuration `unifiedClasspath`.
        unifiedClasspath
                .getDependencies()
                .add(createConfigurationDependency(project, SUBPROJECT_UNIFIED_CONFIGURATION_NAME));

        project.getPluginManager().withPlugin("java", plugin -> {
            String compileClasspathForLock = "consistentVersionsCompile";
            project.getConfigurations().register(compileClasspathForLock, conf -> {
                conf.setDescription("Outgoing configuration for compile-time dependencies meant to be used by "
                        + "consistent-versions");
                conf.setVisible(false); // needn't be visible from other projects
                conf.setCanBeConsumed(true);
                conf.setCanBeResolved(false);
                conf.extendsFrom(project
                        .getConfigurations()
                        .getByName(JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME));
                conf.getAttributes().attribute(GCV_USAGE_ATTRIBUTE, GcvUsage.GCV_INTERNAL);
            });

            String runtimeClasspathForLock = "consistentVersionsRuntime";
            project.getConfigurations().register(runtimeClasspathForLock, conf -> {
                conf.setDescription("Outgoing configuration for runtime dependencies meant to be used by "
                        + "consistent-versions");
                conf.setVisible(false); // needn't be visible from other projects
                conf.setCanBeConsumed(true);
                conf.setCanBeResolved(false);
                conf.extendsFrom(project
                        .getConfigurations()
                        .getByName(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME));
                conf.getAttributes().attribute(GCV_USAGE_ATTRIBUTE, GcvUsage.GCV_INTERNAL);
            });

            project.getConfigurations().named(SUBPROJECT_UNIFIED_CONFIGURATION_NAME).configure(conf -> Stream.of(
                    createConfigurationDependency(project, compileClasspathForLock),
                    createConfigurationDependency(project, runtimeClasspathForLock))
                    .forEach(conf.getDependencies()::add));
        });
    }

    /**
     * Create a dependency to {@code toConfiguration}, where the latter should exist in the given {@code project}.
     */
    private static Dependency createConfigurationDependency(
            Project project, String toConfiguration) {
        return project
                .getDependencies()
                .project(ImmutableMap.of("path", project.getPath(), "configuration", toConfiguration));
    }

    private static void checkPreconditions(Project project) {
        Preconditions.checkState(
                GradleVersion.current().compareTo(MINIMUM_GRADLE_VERSION) >= 0,
                "This plugin requires gradle >= %s",
                MINIMUM_GRADLE_VERSION);

        if (!project.getRootProject().equals(project)) {
            throw new GradleException("Must be applied only to root project");
        }

        project.subprojects(subproject -> {
            subproject.afterEvaluate(sub -> {
                if (haveSameGroupAndName(project, sub)) {
                    throw new GradleException(String.format("This plugin doesn't work if the root project shares both "
                            + "group and name with a subproject. Consider adding the following to settings.gradle:\n"
                            + "rootProject.name = '%s-root'", project.getName()));
                }
            });
        });

        project.subprojects(subproject -> {
            subproject.afterEvaluate(sub -> {
                sub.getConfigurations().configureEach(conf -> {
                    org.gradle.api.internal.artifacts.configurations.ConflictResolution conflictResolution =
                            ((org.gradle.api.internal.artifacts.configurations.ResolutionStrategyInternal)
                                    conf.getResolutionStrategy()).getConflictResolution();
                    if (conflictResolution
                            == org.gradle.api.internal.artifacts.configurations.ConflictResolution.strict) {
                        throw new GradleException("Must not use failOnVersionConflict() for " + conf);
                    }
                });
                sub.getPluginManager().withPlugin("nebula.dependency-recommender", plugin -> {
                    RecommendationProviderContainer container =
                            sub.getExtensions().findByType(RecommendationProviderContainer.class);
                    if (container.getStrategy() == RecommendationStrategies.OverrideTransitives) {
                        throw new GradleException("Must not use strategy OverrideTransitives for " + sub + ". "
                                + "Use this instead: dependencyRecommendations { strategy ConflictResolved }");
                    }
                });
            });
        });
    }

    /**
     Gradle will break if you try to add constraints to any configurations that have been resolved.
     Since unifiedClasspath depends on the SUBPROJECT_UNIFIED_CONFIGURATION_NAME configuration of all
     subprojects (above), that would resolve them when we resolve unifiedClasspath. We need this workaround
     to enable the workflow:

     1. when 'unifiedClasspath' is resolved with --write-locks, it writes the lock file and resolves its
     dependencies
     2. read the lock file
     3. enforce these versions on all subprojects, using constraints

     Since we can't apply these constraints to the already resolved configurations, we need a workaround to
     ensure that unifiedClasspath does not directly depend on subproject configurations that we intend to
     enforce constraints on.
     */
    private void recursivelyCopyProjectDependencies(Project project, DependencySet depSet) {
        // First, set a usage on any "normal" user configurations to disambiguate them
        project.allprojects(subproject -> {
            subproject.getConfigurations().all(conf -> {
                if (!conf.getAttributes().contains(GCV_USAGE_ATTRIBUTE)) {
                    conf.getAttributes().attribute(GCV_USAGE_ATTRIBUTE, GcvUsage.ORIGINAL);
                }
            });
        });
        // from old -> new
        Map<Configuration, String> copiedConfigurationsCache = new HashMap<>();
        recursivelyCopyProjectDependencies(project, depSet, copiedConfigurationsCache);
    }

    /**
     * Recursive method that copies unseen {@link ProjectDependency project dependencies} found in the given {@link
     * DependencySet}, and then amends their {@link ProjectDependency#getTargetConfiguration()} to point to the copied
     * configuration. It then eagerly configures any copied Configurations recursively.
     */
    private void recursivelyCopyProjectDependencies(
            Project currentProject,
            DependencySet dependencySet,
            Map<Configuration, String> copiedConfigurationsCache) {
        dependencySet
                .matching(dependency -> ProjectDependency.class.isAssignableFrom(dependency.getClass()))
                .all(dependency -> {
                    ProjectDependency projectDependency = (ProjectDependency) dependency;
                    Project projectDep = projectDependency.getDependencyProject();

                    String targetConfiguration = projectDependency.getTargetConfiguration();
                    if (targetConfiguration == null) {
                        // Not handling variant-based selection in this code path
                        return;
                    }

                    // We can depend on other configurations from the same project, so don't introduce a cycle.
                    if (projectDep != currentProject) {
                        currentProject.evaluationDependsOn(projectDep.getPath());
                    }

                    Configuration targetConf = projectDep.getConfigurations().getByName(targetConfiguration);
                    Preconditions.checkNotNull(targetConf,
                            "Target configuration of project dependency was null: %s -> %s",
                            dependencySet,
                            projectDep);

                    // First, check if it's an intermediate source, and if so, avoid copying it.
                    if (targetConf.getAttributes().getAttribute(GCV_USAGE_ATTRIBUTE) == GcvUsage.GCV_SOURCE) {
                        log.debug("Not copying configuration with GCV_SOURCE usage: {} -> {}",
                                dependencySet,
                                formatProjectDependency(projectDependency));

                        recursivelyCopyProjectDependencies(
                                projectDep, targetConf.getDependencies(), copiedConfigurationsCache);
                        return;
                    }

                    log.info(
                            "Found legacy project dependency (with target configuration): {} -> {}",
                            dependencySet,
                            formatProjectDependency(projectDependency));

                    if (copiedConfigurationsCache.containsKey(targetConf)) {
                        String copiedConf = copiedConfigurationsCache.get(targetConf);
                        log.debug("Re-using already copied target configuration for dep {} -> {}: {}",
                                currentProject, targetConf, copiedConf);
                        projectDependency.setTargetConfiguration(copiedConf);
                        return;
                    }

                    Configuration copiedConf = targetConf.copyRecursive();
                    copiedConf.setDescription(String.format("Copy of the '%s' configuration that can be resolved by "
                                    + "com.palantir.consistent-versions without resolving the '%s' configuration "
                                    + "itself.",
                            targetConf.getName(), targetConf.getName()));

                    // Update state about what we've seen
                    copiedConfigurationsCache.put(targetConf, copiedConf.getName());

                    if (log.isInfoEnabled()) {
                        log.info(
                                "Recursively copied {}'s '{}' configuration, which has\n"
                                        + " - dependencies: {}\n"
                                        + " - constraints: {}",
                                projectDep, targetConfiguration,
                                ImmutableList.copyOf(copiedConf.getAllDependencies()),
                                ImmutableList.copyOf(copiedConf.getAllDependencyConstraints()));
                    }

                    projectDep.getConfigurations().add(copiedConf);

                    projectDependency.setTargetConfiguration(copiedConf.getName());

                    recursivelyCopyProjectDependencies(
                            projectDep, copiedConf.getDependencies(), copiedConfigurationsCache);
                });
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
        return project.getName().equals(subproject.getName())
                && project.getGroup().equals(subproject.getGroup());
    }

    private void failIfAnyDependenciesUnresolved(ResolutionResult resolutionResult) {
        List<UnresolvedDependencyResult> unresolved = resolutionResult
                .getAllDependencies()
                .stream()
                .filter(a -> a instanceof UnresolvedDependencyResult)
                .map(a -> (UnresolvedDependencyResult) a)
                .collect(Collectors.toList());
        if (!unresolved.isEmpty()) {
            throw new GradleException(String.format(
                    "Could not compute lock state from configuration '%s' due to unresolved dependencies:\n%s",
                    UNIFIED_CLASSPATH_CONFIGURATION_NAME,
                    unresolved
                            .stream()
                            .map(this::formatUnresolvedDependencyResult)
                            .collect(Collectors.joining("\n"))));
        }
    }

    /**
     * Assumes the resolution result succeeded, that is, {@link #failIfAnyDependenciesUnresolved} was run and didn't
     * throw.
     */
    private static FullLockState computeLockState(ResolutionResult resolutionResult) {
        FullLockState.Builder builder = FullLockState.builder();
        resolutionResult.allComponents(component -> {
            extractDependents(component).ifPresent(dependents ->
                    builder.putLines(MyModuleVersionIdentifier.copyOf(component.getModuleVersion()), dependents));
        });
        return builder.build();
    }

    private static Optional<Dependents> extractDependents(ResolvedComponentResult component) {
        if (!(component.getId() instanceof ModuleComponentIdentifier)) {
            return Optional.empty();
        }
        return Optional.of(Dependents.of(component
                .getDependents()
                .stream()
                .filter(dep -> !dep.getRequested().getAttributes().contains(CONSISTENT_VERSIONS_CONSTRAINT_ATTRIBUTE))
                .collect(Collectors.groupingBy(
                        dep -> dep.getFrom().getId(),
                        () -> new TreeMap<>(GradleComparators.COMPONENT_IDENTIFIER_COMPARATOR),
                        Collectors.mapping(
                                dep -> getRequestedVersionConstraint(dep.getRequested()),
                                Collectors.toCollection(
                                        () -> new TreeSet<>(Comparator.comparing(VersionConstraint::toString))))))));
    }

    private static VersionConstraint getRequestedVersionConstraint(ComponentSelector requested) {
        if (requested instanceof ModuleComponentSelector) {
            return ((ModuleComponentSelector) requested).getVersionConstraint();
        }
        throw new RuntimeException(String.format("Expecting a ModuleComponentSelector but found a %s: %s",
                requested.getClass(), requested));
    }

    /**
     * Essentially replicates what
     * {@link org.gradle.api.tasks.diagnostics.internal.insight.DependencyInsightReporter#collectErrorMessages}
     * does, since that whole class is not public API.
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
                Streams
                        .stream(Splitter.on('\n').split(out.getBuffer()))
                        .map(line -> "           " + line + "\n")
                        .forEachOrdered(failures::append);
            }
            failures.append("\n");
        }
        return String.format(" * %s (requested: '%s' because: %s)\n      Failures:\n%s",
                result.getAttempted(),
                result.getRequested(),
                result.getAttemptedReason(),
                failures);
    }

    private static void configureAllProjectsUsingConstraints(Project rootProject, Path gradleLockfile) {
        List<DependencyConstraint> constraints =
                constructConstraintsFromLockFile(gradleLockfile, rootProject.getDependencies().getConstraints());
        rootProject.allprojects(subproject -> configureUsingConstraints(subproject, constraints));
    }

    private static void configureUsingConstraints(
            Project subproject, List<DependencyConstraint> constraints) {
        log.info("Configuring locks for {} using constraints", subproject.getPath());
        // Configure constraints on all configurations that should be locked.
        createTopConfiguration(subproject)
                .configure(conf -> constraints.stream().forEach(conf.getDependencyConstraints()::add));
    }

    private static NamedDomainObjectProvider<Configuration> createTopConfiguration(Project project) {
        NamedDomainObjectProvider<Configuration> locksConfiguration =
                project.getConfigurations().register(LOCK_CONSTRAINTS_CONFIGURATION_NAME, conf -> {
                    conf.setVisible(false);
                    conf.setCanBeConsumed(false);
                    conf.setCanBeResolved(false);
                });

        ImmutableSet<String> configurationNames = ImmutableSet.of(
                Dependency.DEFAULT_CONFIGURATION,
                JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME,
                JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME);
        project
                .getConfigurations()
                .matching(conf -> configurationNames.contains(conf.getName()))
                .configureEach(conf -> conf.extendsFrom(locksConfiguration.get()));

        return locksConfiguration;
    }

    @NotNull
    private static List<DependencyConstraint> constructConstraintsFromLockFile(
            Path gradleLockfile, DependencyConstraintHandler constraintHandler) {
        return new ConflictSafeLockFile(gradleLockfile)
                .readLocks()
                .linesByModuleIdentifier()
                .entrySet()
                .stream()
                .map(e -> e.getKey() + ":" + e.getValue().version())
                // Note: constraints.create sets the version as preferred + required, we want 'strictly' just like
                // gradle does when verifying a lock file.
                .map(notation -> constraintHandler.create(notation, constraint -> {
                    constraint.version(v -> v.strictly(Objects.requireNonNull(constraint.getVersion())));
                    constraint.because("Locked by versions.lock");
                    // We set this in order to identify these constraints later.
                    constraint.attributes(attributeContainer -> {
                        attributeContainer.attribute(CONSISTENT_VERSIONS_CONSTRAINT_ATTRIBUTE, true);
                    });
                }))
                .collect(Collectors.toList());
    }

    /**
     * Allows a resolution with a {@link GcvUsage#GCV_SOURCE} attribute (i.e. the resolution of
     * {@link #UNIFIED_CLASSPATH_CONFIGURATION_NAME}) to depend on configurations with the {@link GcvUsage#GCV_INTERNAL}
     * attribute.
     * <p>
     * This is required for {@link #SUBPROJECT_UNIFIED_CONFIGURATION_NAME} to be able to depend on the other
     * configurations that we actually aggregate (consistentVersionsCompile, consistentVersionsRuntime etc).
     */
    static class ConsistentVersionsCompatibilityRules implements AttributeCompatibilityRule<GcvUsage> {
        @Override
        public void execute(CompatibilityCheckDetails<GcvUsage> details) {
            GcvUsage consumer = details.getConsumerValue();
            GcvUsage producer = details.getProducerValue();
            if (GcvUsage.GCV_SOURCE.equals(consumer) && GcvUsage.GCV_INTERNAL.equals(producer)) {
                details.compatible();
            }
        }
    }
}
