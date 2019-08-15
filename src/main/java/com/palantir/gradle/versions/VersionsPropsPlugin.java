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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
import org.gradle.api.publish.maven.MavenPublication;
import org.gradle.util.GradleVersion;

public class VersionsPropsPlugin implements Plugin<Project> {
    private static final Logger log = Logging.getLogger(VersionsPropsPlugin.class);
    private static final String ROOT_CONFIGURATION_NAME = "rootConfiguration";
    private static final GradleVersion MINIMUM_GRADLE_VERSION = GradleVersion.version("5.1");

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
                    conf.setVisible(false);
                });

        project.getConfigurations().configureEach(conf -> {
            if (conf.getName().equals(ROOT_CONFIGURATION_NAME)) {
                return;
            }
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

        // Gradle 5.1 has a bug whereby a platform dependency whose version comes from a separate constraint end
        // up as two separate entries in the resulting POM, making it invalid.
        // https://github.com/gradle/gradle/issues/8238
        project.getPluginManager().withPlugin("publishing", plugin -> {
            PublishingExtension publishingExtension =
                    project.getExtensions().getByType(PublishingExtension.class);
            publishingExtension.getPublications().withType(MavenPublication.class, publication -> {
                log.info("Fixing pom publication for {}: {}", project, publication);
                publication.getPom().withXml(xmlProvider -> {
                    GradleWorkarounds.mergeImportsWithVersions(xmlProvider.asElement());
                });
            });
        });
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

        if (conf.getName().equals(JavaPlugin.RUNTIME_ELEMENTS_CONFIGURATION_NAME)
                || conf.getName().equals(JavaPlugin.API_ELEMENTS_CONFIGURATION_NAME)) {
            log.debug("Only configuring BOM dependencies on published java configuration: {}", conf);
            conf.getDependencies().addAllLater(extractPlatformDependencies(subproject, rootConfiguration));
            return;
        }

        // Must do all this in a withDependencies block so that it's run lazily, so that
        // `extension.shouldExcludeConfiguration` isn't queried too early.
        // However, we must not make this lazy using an afterEvaluate.
        // The reason for that is because we want to ensure we set this up before VersionsLockPlugin's
        // unifiedClasspath gets resolved (in afterEvaluate of the root project), and if we're currently setting up
        // configurations in the root project, _our_ afterEvaluate might then be run after _VersionsLockPlugin_'s
        // afterEvaluate, leading to sadness.
        // This way however, we guarantee that this is evaluated exactly once and right at the moment when
        // conf.getDependencies() is called.
        conf.withDependencies(deps -> {
            if (extension.shouldExcludeConfiguration(conf.getName())) {
                log.debug("Not configuring {} because it's excluded", conf);
                return;
            }
            // Because of https://github.com/gradle/gradle/issues/7954, we need to manually inject versions
            // of direct dependencies if they come from a *-constraint
            // Note: this is necessary on the rootConfiguration too in order to support injecting versions of
            // BOMs.
            configureDirectDependencyInjection(versionsProps, deps);

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
     */
    private static void configureDirectDependencyInjection(VersionsProps versionsProps, DependencySet deps) {
        deps.withType(ExternalDependency.class).configureEach(moduleDependency -> {
            if (moduleDependency.getVersion() != null) {
                return;
            }
            versionsProps
                    .getRecommendedVersion(moduleDependency.getModule())
                    .ifPresent(version -> moduleDependency.version(constraint -> {
                        log.info("Found direct dependency without version: {} -> {}, requiring: {}",
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
}
