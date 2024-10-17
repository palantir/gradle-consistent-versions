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
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.gradle.api.GradleException;
import org.gradle.api.NamedDomainObjectProvider;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.ComponentMetadataDetails;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.DependencyConstraint;
import org.gradle.api.artifacts.DependencySet;
import org.gradle.api.artifacts.ExternalDependency;
import org.gradle.api.artifacts.ModuleDependency;
import org.gradle.api.artifacts.ProjectDependency;
import org.gradle.api.attributes.Usage;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Provider;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.publish.VariantVersionMappingStrategy;
import org.gradle.api.publish.maven.MavenPublication;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.language.base.plugins.LifecycleBasePlugin;
import org.gradle.util.GradleVersion;

public class VersionsPropsPlugin implements Plugin<Project> {
    private static final Logger log = Logging.getLogger(VersionsPropsPlugin.class);
    private static final String ROOT_CONFIGURATION_NAME = "rootConfiguration";
    private static final GradleVersion MINIMUM_GRADLE_VERSION = GradleVersion.version("5.2");
    private static final ImmutableSet<String> JAVA_PUBLISHED_CONFIGURATION_NAMES =
            ImmutableSet.of(JavaPlugin.RUNTIME_ELEMENTS_CONFIGURATION_NAME, JavaPlugin.API_ELEMENTS_CONFIGURATION_NAME);
    private static final String GCV_VERSIONS_PROPS_CONSTRAINTS_CONFIGURATION_NAME = "gcvVersionsPropsConstraints";
    private static final String VERSION_PROPS_EXTENSION = "versionsProps";

    @Override
    public final void apply(Project project) {
        checkPreconditions();

        // Shared across root project / other project
        // This must be usable during VersionsLockPlugin's resolution of unifiedClasspath, so the usage
        // must be 'compatible with' (or the same as) the one for the VersionsLockPlugin's own configurations.
        Usage gcvVersionsPropsUsage =
                project.getObjects().named(Usage.class, ConsistentVersionsPlugin.CONSISTENT_VERSIONS_USAGE);
        String gcvVersionsPropsCapability = "gcv:versions-props:0";

        VersionsProps versionsProps = getVersionsProps(project.getRootProject());

        if (project.getRootProject().equals(project)) {
            applyToRootProject(project);

            TaskProvider<CheckUnusedConstraintsTask> checkNoUnusedConstraints = project.getTasks()
                    .register("checkUnusedConstraints", CheckUnusedConstraintsTask.class, task -> {
                        if (project.getGradle().getStartParameter().isConfigureOnDemand()
                                && project.getAllprojects().stream()
                                        .anyMatch(p -> !p.getState().getExecuted())) {
                            task.setShouldFailWithConfigurationOnDemandMessage(true);
                        } else {
                            task.getClasspath().set(project.provider(() -> project.getAllprojects().stream()
                                    .flatMap(proj -> CheckUnusedConstraintsTask.getResolvedModuleIdentifiers(
                                            proj,
                                            project.getExtensions().getByType(VersionRecommendationsExtension.class)))
                                    .collect(Collectors.toSet())));
                        }
                        task.getPropsFile()
                                .set(project.getLayout().getProjectDirectory().file("versions.props"));
                    });
            project.getTasks().named("check").configure(task -> task.dependsOn(checkNoUnusedConstraints));

            TaskProvider<CheckOverbroadConstraints> checkBadPins = project.getTasks()
                    .register("checkBadPins", CheckOverbroadConstraints.class, task -> {
                        task.getLockFile()
                                .set(project.getLayout().getProjectDirectory().file("versions.lock"));
                        task.getPropsFile()
                                .set(project.getLayout().getProjectDirectory().file("versions.props"));
                    });
            project.getTasks().named("check").configure(task -> task.dependsOn(checkBadPins));

            // Create "platform" configuration in root project, which will hold the versions props constraints
            project.getConfigurations().register(GCV_VERSIONS_PROPS_CONSTRAINTS_CONFIGURATION_NAME, conf -> {
                conf.getAttributes().attribute(Usage.USAGE_ATTRIBUTE, gcvVersionsPropsUsage);
                conf.getOutgoing().capability(gcvVersionsPropsCapability);
                conf.setCanBeResolved(false);
                conf.setCanBeConsumed(true);
                conf.setVisible(false);

                addVersionsPropsConstraints(project.getDependencies().getConstraints()::create, conf, versionsProps);
            });
        }

        VersionRecommendationsExtension extension =
                project.getRootProject().getExtensions().getByType(VersionRecommendationsExtension.class);

        NamedDomainObjectProvider<Configuration> rootConfiguration = project.getConfigurations()
                .register(ROOT_CONFIGURATION_NAME, conf -> {
                    conf.setCanBeResolved(false);
                    conf.setCanBeConsumed(false);
                    conf.setVisible(false);

                    // Wire in the constraints from the main configuration.
                    conf.getDependencies()
                            .add(createDepOnRootConstraintsConfiguration(
                                    project, gcvVersionsPropsUsage, gcvVersionsPropsCapability));
                });

        project.getConfigurations().configureEach(conf -> {
            setupConfiguration(project, extension, rootConfiguration.get(), versionsProps, conf);
        });

        log.debug("Configuring rules to assign *-constraints to platforms in {}", project);
        project.getDependencies()
                .getComponents()
                .all(component -> tryAssignComponentToPlatform(versionsProps, component));

        // This is to ensure that we're not producing broken POMs due to missing versions
        configureResolvedVersionsWithVersionMapping(project);
    }

    private static ProjectDependency createDepOnRootConstraintsConfiguration(
            Project project, Usage usage, String capability) {
        ProjectDependency projectDep =
                ((ProjectDependency) project.getDependencies().create(project.getRootProject()));
        projectDep.capabilities(capabilities -> capabilities.requireCapability(capability));
        projectDep.attributes(attrs -> attrs.attribute(Usage.USAGE_ATTRIBUTE, usage));
        return projectDep;
    }

    private static void applyToRootProject(Project project) {
        project.getPluginManager().apply(LifecycleBasePlugin.class);
        project.getExtensions()
                .create(VersionRecommendationsExtension.EXTENSION, VersionRecommendationsExtension.class, project);

        project.subprojects(subproject -> subproject.getPluginManager().apply(VersionsPropsPlugin.class));
    }

    private static void setupConfiguration(
            Project subproject,
            VersionRecommendationsExtension extension,
            Configuration rootConfiguration,
            VersionsProps versionsProps,
            Configuration conf) {
        // We only expect 'platform' dependencies to be declared in rootConfiguration.
        // This injects missing versions, in case the version comes from a *-dependency in versions.props.
        // For rootConfiguration, unlike other configurations, this is the only customization necessary.
        if (conf.getName().equals(ROOT_CONFIGURATION_NAME)) {
            conf.withDependencies(deps -> provideVersionsFromStarDependencies(versionsProps, deps));
            return;
        }

        if (conf.getName().equals(GCV_VERSIONS_PROPS_CONSTRAINTS_CONFIGURATION_NAME)) {
            return;
        }

        // We must do this addAllLater as soon as possible, otherwise 'conf' could get observed early
        // by some other configuration that depends on it being resolved, and then we can't modify it anymore.
        // This can happen if some other configuration depends on 'conf' *intransitively*.
        // These configurations can never be excluded anyway so we don't need the laziness.
        if (JAVA_PUBLISHED_CONFIGURATION_NAMES.contains(conf.getName())) {
            log.debug("Only configuring BOM dependencies on published java configuration: {}", conf);
            conf.getDependencies().addAllLater(extractPlatformDependencies(subproject, rootConfiguration));
            return;
        }

        // Must do all this in a withDependencies block so that it's run lazily, so that
        // `extension.shouldExcludeConfiguration` isn't queried too early (before the user had the change to configure).
        // However, we must not make this lazy using an afterEvaluate.
        // The reason for that is because we want to ensure we set this up before VersionsLockPlugin's
        // unifiedClasspath gets resolved (in afterEvaluate of the root project), and if we're currently setting up
        // configurations in the root project, _our_ afterEvaluate might then be run after _VersionsLockPlugin_'s
        // afterEvaluate, leading to sadness.
        // This way however, we guarantee that this is evaluated exactly once and right at the moment when
        // conf.getDependencies() is called.
        AtomicBoolean wasConfigured = new AtomicBoolean();
        conf.withDependencies(deps -> {
            if (!wasConfigured.compareAndSet(false, true)) {
                // We are configuring a copy of the original dependency, as they inherit the withDependenciesActions.
                log.debug("Not configuring {} because it's a copy of an already configured configuration.", conf);
                return;
            }
            if (extension.shouldExcludeConfiguration(conf.getName())) {
                log.debug("Not configuring {} because it's excluded", conf);
                return;
            }

            // This will ensure that dependencies declared in almost all configurations - including ancestors of
            // published configurations (such as `compile`, `runtimeOnly`) - have a version if there only
            // a star-constraint in versions.props that matches them.
            provideVersionsFromStarDependencies(versionsProps, deps);

            // But don't configure any _ancestors_ of our published configurations to extend rootConfiguration, as we
            // explicitly DO NOT WANT to republish the constraints that come from it (that come from versions.props).
            if (configurationWillAffectPublishedConstraints(subproject, conf)) {
                log.debug("Not configuring published java configuration or its ancestor: {}", conf);
                return;
            }

            conf.extendsFrom(rootConfiguration);

            // We must allow unifiedClasspath to be resolved at configuration-time.
            if (VersionsLockPlugin.UNIFIED_CLASSPATH_CONFIGURATION_NAME.equals(conf.getName())) {
                return;
            }

            // Add fail-safe error reporting
            conf.getIncoming().beforeResolve(_resolvableDependencies -> {
                if (GradleWorkarounds.isConfiguring(subproject.getState())) {
                    throw new GradleException(String.format(
                            "Not allowed to resolve %s at "
                                    + "configuration time (https://guides.gradle.org/performance/"
                                    + "#don_t_resolve_dependencies_at_configuration_time). Please upgrade your "
                                    + "plugins and double-check your gradle scripts (see stacktrace)",
                            conf));
                }
            });
        });
    }

    private static boolean configurationWillAffectPublishedConstraints(Project subproject, Configuration conf) {
        return JAVA_PUBLISHED_CONFIGURATION_NAMES.stream()
                .anyMatch(confName -> isSameOrSuperconfigurationOf(subproject, conf, confName));
    }

    private static boolean isSameOrSuperconfigurationOf(
            Project project, Configuration conf, String targetConfigurationName) {
        if (project.getConfigurations().findByName(targetConfigurationName) == null) {
            // this may happens if the project doesn't have 'java' applied, so the configuration was never created
            return false;
        }

        Configuration targetConf = project.getConfigurations().getByName(targetConfigurationName);
        return targetConf.getHierarchy().contains(conf);
    }

    private static Provider<List<Dependency>> extractPlatformDependencies(
            Project project, Configuration rootConfiguration) {
        ListProperty<Dependency> proxiedDependencies = project.getObjects().listProperty(Dependency.class);
        proxiedDependencies.addAll(project.provider(() -> rootConfiguration
                .getDependencies()
                .withType(ModuleDependency.class)
                .matching(dep -> GradleWorkarounds.isPlatform(dep.getAttributes()))));
        return GradleWorkarounds.fixListProperty(proxiedDependencies);
    }

    /**
     * For dependencies inside {@code deps} that don't have a version, sets a version if there is a corresponding
     * platform constraint (one containing at least a {@code *} character).
     *
     * <p>This is necessary because virtual platforms don't do dependency injection, see <a
     * href=https://github.com/gradle/gradle/issues/7954>gradle/gradle#7954</a>
     */
    private static void provideVersionsFromStarDependencies(VersionsProps versionsProps, DependencySet deps) {
        deps.withType(ExternalDependency.class).configureEach(moduleDependency -> {
            if (moduleDependency.getVersion() != null) {
                return;
            }
            versionsProps
                    .getStarVersion(moduleDependency.getModule())
                    .ifPresent(version -> moduleDependency.version(constraint -> {
                        log.debug(
                                "Found direct dependency without version: {} -> {}, requiring: {}",
                                deps,
                                moduleDependency,
                                version);
                        constraint.require(version);
                    }));
        });
    }

    /**
     * Try to assign the {@code component} to the appropriate platform, if there is a rule matching it in the given
     * {@link VersionsProps}.
     */
    private static void tryAssignComponentToPlatform(VersionsProps versionsProps, ComponentMetadataDetails component) {
        log.debug("Configuring component: {}", component);
        versionsProps.getPlatform(component.getId().getModule()).ifPresent(platform -> {
            String platformNotation = platform + ":" + component.getId().getVersion();
            log.debug("Assigning component {} to virtual platform {}", component, platformNotation);
            component.belongsTo(platformNotation);
        });
    }

    private static void addVersionsPropsConstraints(
            DependencyConstraintCreator constraintCreator, Configuration conf, VersionsProps versionsProps) {
        ImmutableList<DependencyConstraint> constraints =
                versionsProps.constructConstraints(constraintCreator).collect(ImmutableList.toImmutableList());
        log.debug("Adding constraints to {}: {}", conf, constraints);
        constraints.forEach(conf.getDependencyConstraints()::add);
    }

    private static VersionsProps getVersionsProps(Project rootProject) {
        VersionsProps versionsProps = rootProject.getExtensions().findByType(VersionsProps.class);
        if (versionsProps == null) {
            versionsProps = loadVersionsProps(rootProject.file("versions.props").toPath());
            rootProject.getExtensions().add(VERSION_PROPS_EXTENSION, versionsProps);
        }
        return versionsProps;
    }

    private static VersionsProps loadVersionsProps(Path versionsPropsFile) {
        if (!Files.exists(versionsPropsFile)) {
            return VersionsProps.empty();
        }
        log.debug("Configuring constraints from properties file {}", versionsPropsFile);
        return VersionsProps.loadFromFile(versionsPropsFile);
    }

    private static void checkPreconditions() {
        Preconditions.checkState(
                GradleVersion.current().compareTo(MINIMUM_GRADLE_VERSION) >= 0,
                "This plugin requires gradle >= %s",
                MINIMUM_GRADLE_VERSION);
    }

    private static void configureResolvedVersionsWithVersionMapping(Project project) {
        project.getPluginManager().withPlugin("maven-publish", _plugin -> {
            project.getExtensions()
                    .getByType(PublishingExtension.class)
                    .getPublications()
                    .withType(MavenPublication.class)
                    .configureEach(publication -> publication.versionMapping(mapping -> {
                        mapping.allVariants(VariantVersionMappingStrategy::fromResolutionResult);
                    }));
        });
    }
}
