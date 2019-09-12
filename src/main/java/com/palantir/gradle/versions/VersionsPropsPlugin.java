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
import org.gradle.api.artifacts.dsl.DependencyConstraintHandler;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Provider;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.publish.VariantVersionMappingStrategy;
import org.gradle.api.publish.maven.MavenPublication;
import org.gradle.util.GradleVersion;

public class VersionsPropsPlugin implements Plugin<Project> {
    private static final Logger log = Logging.getLogger(VersionsPropsPlugin.class);
    private static final String ROOT_CONFIGURATION_NAME = "rootConfiguration";
    private static final GradleVersion MINIMUM_GRADLE_VERSION = GradleVersion.version("5.2");
    private static final ImmutableSet<String> JAVA_PUBLISHED_CONFIGURATION_NAMES = ImmutableSet.of(
            JavaPlugin.RUNTIME_ELEMENTS_CONFIGURATION_NAME,
            JavaPlugin.API_ELEMENTS_CONFIGURATION_NAME);

    @Override
    public final void apply(Project project) {
        checkPreconditions();
        if (project.getRootProject().equals(project)) {
            applyToRootProject(project);
        }

        VersionRecommendationsExtension extension =
                project.getRootProject().getExtensions().getByType(VersionRecommendationsExtension.class);

        VersionsProps versionsProps = loadVersionsProps(project.getRootProject().file("versions.props").toPath());

        NamedDomainObjectProvider<Configuration> rootConfiguration =
                project.getConfigurations().register(ROOT_CONFIGURATION_NAME, conf -> {
                    conf.setCanBeResolved(false);
                    conf.setVisible(false);
                });

        project.getConfigurations().configureEach(conf -> {
            setupConfiguration(project, extension, rootConfiguration.get(), versionsProps, conf);
        });

        // Note: don't add constraints to this, only call `create` / `platform` on it.
        DependencyConstraintHandler constraintHandler = project.getDependencies().getConstraints();
        rootConfiguration.configure(conf ->
                addVersionsPropsConstraints(constraintHandler, conf, versionsProps));

        log.info("Configuring rules to assign *-constraints to platforms in {}", project);
        project.getDependencies()
                .getComponents()
                .all(component -> tryAssignComponentToPlatform(versionsProps, component));

        // This is to ensure that we're not producing broken POMs due to missing versions
        configureResolvedVersionsWithVersionMapping(project);
    }

    private static void applyToRootProject(Project project) {
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

        // We must do this addAllLater as soon as possible, otherwise conf.getDependencies() could get realized
        // early by some other plugin and then we can't modify it anymore.
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
        });

        // We must allow unifiedClasspath to be resolved at configuration-time.
        if (VersionsLockPlugin.UNIFIED_CLASSPATH_CONFIGURATION_NAME.equals(conf.getName())) {
            return;
        }

        // Add fail-safe error reporting
        conf.getIncoming().beforeResolve(resolvableDependencies -> {
            if (GradleWorkarounds.isConfiguring(subproject.getState())) {
                throw new GradleException(String.format("Not allowed to resolve %s at "
                        + "configuration time (https://guides.gradle.org/performance/"
                        + "#don_t_resolve_dependencies_at_configuration_time). Please upgrade your "
                        + "plugins and double-check your gradle scripts (see stacktrace)", conf));
            }
        });
    }

    private static boolean configurationWillAffectPublishedConstraints(Project subproject, Configuration conf) {
        return JAVA_PUBLISHED_CONFIGURATION_NAMES
                .stream()
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
        proxiedDependencies.addAll(project.provider(() -> rootConfiguration.getDependencies()
                .withType(ModuleDependency.class)
                .matching(dep -> GradleWorkarounds.isPlatform(dep.getAttributes()))));
        return GradleWorkarounds.fixListProperty(proxiedDependencies);
    }

    /**
     * For dependencies inside {@code deps} that don't have a version, sets a version if there is a corresponding
     * platform constraint (one containing at least a {@code *} character).
     * <p>
     * This is necessary because virtual platforms don't do dependency injection, see
     * <a href=https://github.com/gradle/gradle/issues/7954>gradle/gradle#7954</a>
     */
    private static void provideVersionsFromStarDependencies(VersionsProps versionsProps, DependencySet deps) {
        deps.withType(ExternalDependency.class).configureEach(moduleDependency -> {
            if (moduleDependency.getVersion() != null) {
                return;
            }
            versionsProps
                    .getStarVersion(moduleDependency.getModule())
                    .ifPresent(version -> moduleDependency.version(constraint -> {
                        log.debug("Found direct dependency without version: {} -> {}, requiring: {}",
                                deps, moduleDependency, version);
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
            log.info("Assigning component {} to virtual platform {}", component, platformNotation);
            component.belongsTo(platformNotation);
        });
    }

    private static void addVersionsPropsConstraints(
            DependencyConstraintHandler constraintHandler, Configuration conf, VersionsProps versionsProps) {
        ImmutableList<DependencyConstraint> constraints =
                versionsProps.constructConstraints(constraintHandler).collect(ImmutableList.toImmutableList());
        log.info("Adding constraints to {}: {}", conf, constraints);
        constraints.forEach(conf.getDependencyConstraints()::add);
    }

    private static VersionsProps loadVersionsProps(Path versionsPropsFile) {
        if (!Files.exists(versionsPropsFile)) {
            return VersionsProps.empty();
        }
        log.info("Configuring constraints from properties file {}", versionsPropsFile);
        return VersionsProps.loadFromFile(versionsPropsFile);
    }

    private static void checkPreconditions() {
        Preconditions.checkState(
                GradleVersion.current().compareTo(MINIMUM_GRADLE_VERSION) >= 0,
                "This plugin requires gradle >= %s",
                MINIMUM_GRADLE_VERSION);
    }

    private static void configureResolvedVersionsWithVersionMapping(Project project) {
        project.getPluginManager().withPlugin("maven-publish", plugin -> {
            project
                    .getExtensions()
                    .getByType(PublishingExtension.class)
                    .getPublications()
                    .withType(MavenPublication.class)
                    .configureEach(publication -> publication.versionMapping(mapping -> {
                        mapping.allVariants(VariantVersionMappingStrategy::fromResolutionResult);
                    }));
        });
    }
}
