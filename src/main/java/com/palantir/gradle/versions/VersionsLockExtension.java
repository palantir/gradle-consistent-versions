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
import java.util.Set;
import javax.inject.Inject;
import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.SourceSet;

public class VersionsLockExtension {
    private final Project project;
    private final SetProperty<String> productionConfigurations;
    private final SetProperty<String> testConfigurations;
    private final ScopeConfigurer productionConfigurer;
    private final ScopeConfigurer testConfigurer;
    private final Property<Boolean> useJavaPluginDefaults;

    @Inject
    public VersionsLockExtension(Project project) {
        this.project = project;
        this.useJavaPluginDefaults =
                project.getObjects().property(Boolean.class).convention(true);
        this.productionConfigurations =
                project.getObjects().setProperty(String.class).empty();
        this.testConfigurations = project.getObjects().setProperty(String.class).empty();
        this.productionConfigurer = new ScopeConfigurer(productionConfigurations);
        this.testConfigurer = new ScopeConfigurer(testConfigurations);
    }

    public final void production(Action<ScopeConfigurer> action) {
        action.execute(productionConfigurer);
    }

    public final void test(Action<ScopeConfigurer> action) {
        action.execute(testConfigurer);
    }

    public final void disableJavaPluginDefaults() {
        useJavaPluginDefaults.set(false);
    }

    public final void testProject() {
        disableJavaPluginDefaults();
        Preconditions.checkArgument(
                project.getPluginManager().hasPlugin("java"),
                "The java plugin must be applied to consider this a test project: %s",
                project);
        project.getConvention()
                .getPlugin(JavaPluginConvention.class)
                .getSourceSets()
                .all(testConfigurer::from);
    }

    final boolean isUseJavaPluginDefaults() {
        useJavaPluginDefaults.finalizeValue();
        return useJavaPluginDefaults.get();
    }

    final Set<String> getProductionConfigurations() {
        // Prevent cheeky groovy code from accessing this and modifying the set
        productionConfigurations.finalizeValue();
        return productionConfigurations.get();
    }

    final Set<String> getTestConfigurations() {
        // Prevent cheeky groovy code from accessing this and modifying the set
        testConfigurations.finalizeValue();
        return testConfigurations.get();
    }

    public static final class ScopeConfigurer {
        private final SetProperty<String> configurations;

        public ScopeConfigurer(SetProperty<String> configurations) {
            this.configurations = configurations;
        }

        public void from(String configuration) {
            configurations.add(configuration);
        }

        public void from(SourceSet sourceSet) {
            from(sourceSet.getCompileClasspathConfigurationName());
            from(sourceSet.getRuntimeClasspathConfigurationName());
        }
    }
}
