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

import com.palantir.gradle.ideaconfiguration.IdeaConfigurationExtension;
import com.palantir.gradle.ideaconfiguration.IdeaConfigurationPlugin;
import javax.inject.Inject;
import org.gradle.api.GradleException;
import org.gradle.api.Named;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.attributes.Attribute;
import org.gradle.api.attributes.AttributesSchema;
import org.gradle.api.attributes.Usage;
import org.gradle.api.internal.GradleInternal;
import org.gradle.api.invocation.Gradle;
import org.gradle.api.model.ObjectFactory;
import org.gradle.util.GradleVersion;

public class ConsistentVersionsPlugin implements Plugin<Project> {
    static final String CONSISTENT_VERSIONS_USAGE = "consistent-versions-usage";

    @Override
    public final void apply(Project project) {
        if (!project.getRootProject().equals(project)) {
            throw new GradleException("Must be applied only to root project");
        }
        project.allprojects(p -> {
            AttributesSchema attributesSchema = p.getDependencies().getAttributesSchema();
            attributesSchema.attribute(GcvBuildPath.ATTRIBUTE);
        });
        project.getPluginManager().apply(VersionsLockPlugin.class);
        project.getPluginManager().apply(VersionsPropsPlugin.class);
        project.getPluginManager().apply(GetVersionPlugin.class);
        project.getPluginManager().apply(VersionsPropsIdeaPlugin.class);

        project.getPluginManager().apply(IdeaConfigurationPlugin.class);
        IdeaConfigurationExtension extension = project.getExtensions().getByType(IdeaConfigurationExtension.class);
        extension.getExternalDependencies().register("gradle-consistent-versions", dep -> dep.atLeastVersion("0.9.0"));

        project.allprojects(proj -> {
            proj.getPluginManager().withPlugin("java", _plugin -> {
                proj.getPluginManager().apply(FixLegacyJavaConfigurationsPlugin.class);
            });
        });
    }

    public abstract static class GcvAttributes {
        @Inject
        protected abstract Gradle getGradle();

        @Inject
        protected abstract ObjectFactory getObjects();

        /**
         * We don't want the consumable configurations we create to have any known usage, so we give them this usage.
         * This is so that:
         *
         * <ul>
         *   <li>they don't cause an ambiguity between the copied and the original {@code apiElements},
         *       {@code runtimeElements} etc., when a resolution with a required usage is performed (such as by resolving a
         *       {@code compileClasspath} or {@code runtimeClasspath} configuration)
         *   <li>to avoid the configurations we create to calculate locks being resolved as an actual candidate in normal
         *       resolution, when all other candidates didn't match, simply because it had completely distinct attributes
         *       from the requested attributes.
         * </ul>
         */
        public final Usage gradleUsageForGcv() {
            return getObjects().named(Usage.class, CONSISTENT_VERSIONS_USAGE);
        }

        /**
         * Whilst calculating the {@code unifiedClasspath} for a parent build, we don't want internal configurations
         * from <em>child</em> builds - i.e. included builds - to be selected. We effectively want to consume the
         * "published" variant i.e. if the included build were to be an external library we were to consume, what
         * dependencies and jars do we get?
         */
        public final GcvBuildPath buildPath() {
            return getObjects().named(GcvBuildPath.class, buildPathName());
        }

        private String buildPathName() {
            if (GradleVersion.current().compareTo(GradleVersion.version("8.3")) >= 0) {
                return getGradle().getRootProject().getBuildTreePath();
            } else {
                return ((GradleInternal) getGradle()).getIdentityPath().getPath();
            }
        }
    }

    public abstract static class GcvBuildPath implements Named {
        public static final Attribute<GcvBuildPath> ATTRIBUTE =
                Attribute.of("com.palantir.consistent-versions.build-path", GcvBuildPath.class);
    }

}
