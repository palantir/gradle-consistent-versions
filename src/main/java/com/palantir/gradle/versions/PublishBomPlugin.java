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

import com.google.common.collect.ImmutableList;
import org.gradle.api.GradleException;
import org.gradle.api.Incubating;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.plugins.JavaBasePlugin;
import org.gradle.api.plugins.JavaPlatformPlugin;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.publish.maven.MavenPublication;
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin;
import org.gradle.util.GradleVersion;

/**
 * Sets up a {@code bom} publication that publishes constraints for _all_ dependencies found in the lock file that is
 * managed by {@link VersionsLockPlugin}.
 */
@Incubating
public class PublishBomPlugin implements Plugin<Project> {
    private static final Logger log = Logging.getLogger(PublishBomPlugin.class);
    private static final GradleVersion MINIMUM_GRADLE_VERSION = GradleVersion.version("5.2");
    private static final String JAVA_PLATFORM_COMPONENT = "javaPlatform";
    private static final String VERSIONS_LOCK_PLUGIN = "com.palantir.versions-lock";
    private static final String PUBLISH_BOM_PLUGIN = "com.palantir.publish-bom";
    private static final String VERSIONS_PROPS_PLUGIN = "com.palantir.versions-props";

    private static final ImmutableList<String> JAVA_PLATFORM_CONFIGURATIONS = ImmutableList.of(
            JavaPlatformPlugin.API_CONFIGURATION_NAME,
            JavaPlatformPlugin.API_ELEMENTS_CONFIGURATION_NAME,
            JavaPlatformPlugin.ENFORCED_API_ELEMENTS_CONFIGURATION_NAME,
            JavaPlatformPlugin.RUNTIME_CONFIGURATION_NAME,
            JavaPlatformPlugin.RUNTIME_ELEMENTS_CONFIGURATION_NAME,
            JavaPlatformPlugin.ENFORCED_RUNTIME_ELEMENTS_CONFIGURATION_NAME,
            JavaPlatformPlugin.CLASSPATH_CONFIGURATION_NAME);

    @Override
    public final void apply(Project project) {
        checkPreconditions(project);

        project.getPluginManager().apply(MavenPublishPlugin.class);
        project.getPluginManager().apply(JavaPlatformPlugin.class);

        // Configure root project versions-lock
        project.getRootProject().getPluginManager().withPlugin(VERSIONS_LOCK_PLUGIN, plugin -> {
            VersionsLockPlugin.applyLocksTo(project, "api");
        });

        // If versions-props is applied, make it so that it doesn't apply its recommendations to any of the
        // javaPlatform's configurations.
        project.getRootProject().getPluginManager().withPlugin(VERSIONS_PROPS_PLUGIN, plugin -> {
            JAVA_PLATFORM_CONFIGURATIONS.forEach(name -> project.getConfigurations().named(name).configure(conf -> {
                // Mark it so it doesn't receive constraints from VersionsPropsPlugin
                conf.getAttributes().attribute(VersionsPropsPlugin.CONFIGURATION_EXCLUDE_ATTRIBUTE, true);
            }));
        });

        project.getGradle().projectsEvaluated(gradle -> {
            if (!gradle.getRootProject().getPluginManager().hasPlugin(VERSIONS_LOCK_PLUGIN)) {
                throw new GradleException("Need to apply " + VERSIONS_LOCK_PLUGIN + " on the root project when using "
                        + PUBLISH_BOM_PLUGIN);
            }
        });

        PublishingExtension ourPublishingExtension = project.getExtensions().getByType(PublishingExtension.class);
        ourPublishingExtension.getPublications().register("bom", MavenPublication.class, publication -> {
            publication.from(project.getComponents().getByName(JAVA_PLATFORM_COMPONENT));
        });
    }

    private static void checkPreconditions(Project project) {
        if (GradleVersion.current().compareTo(MINIMUM_GRADLE_VERSION) < 0) {
            throw new GradleException("The publish-bom plugin requires at least gradle " + MINIMUM_GRADLE_VERSION);
        }

        // JavaPlatformPlugin is incompatible with JavaBasePlugin
        if (project.getPlugins().hasPlugin(JavaBasePlugin.class)) {
            failBecauseJavaPluginApplied(project);
        }

        project
                .getPlugins()
                .withType(JavaBasePlugin.class)
                .configureEach(plugin -> failBecauseJavaPluginApplied(project));
    }

    private static void failBecauseJavaPluginApplied(Project project) {
        throw new GradleException("Cannot apply " + PublishBomPlugin.class + " to project that has the java "
                + "plugin applied: " + project);
    }
}
