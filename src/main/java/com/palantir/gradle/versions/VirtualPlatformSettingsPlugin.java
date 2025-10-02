/*
 * (c) Copyright 2025 Palantir Technologies Inc. All rights reserved.
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

import org.gradle.api.Plugin;
import org.gradle.api.artifacts.ComponentMetadataContext;
import org.gradle.api.artifacts.ComponentMetadataRule;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.initialization.Settings;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.plugins.JavaPlugin;

public class VirtualPlatformSettingsPlugin implements Plugin<Settings> {

    private static final Logger log = Logging.getLogger(VirtualPlatformSettingsPlugin.class);
    private static final String VIRTUAL_PLATFORM_MODULE = "palantir-virtual-platform";

    @Override
    public final void apply(Settings settings) {

        settings.getGradle().allprojects(project -> {
            project.getPluginManager().apply(JavaPlugin.class);
            project.getConfigurations()
                    .getByName("runtimeClasspath")
                    .getIncoming()
                    .beforeResolve(_beforeResolve -> {
                        System.out.println("before");
                    });
            project.getConfigurations()
                    .getByName("runtimeClasspath")
                    .getIncoming()
                    .afterResolve(_beforeResolve -> {
                        System.out.println("after");
                    });
            //            project.getDependencies().getComponents().all(this::discoverPlatform);

            project.getDependencies().getComponents().all(ConsistentErrorPronePlatformRule.class);
        });
    }

    //    private void discoverPlatform(ComponentMetadataDetails component) {
    //        component.allVariants(variant -> variant.withDependencyConstraints(
    //                constraints -> constraints.forEach(constraint -> discoverPlatform(component, constraint))));
    //    }
    //
    //    private void discoverPlatform(ComponentMetadataDetails component, DependencyConstraintMetadata constraint) {
    //
    //        if (!VIRTUAL_PLATFORM_MODULE.equals(constraint.getName())) {
    //            return;
    //        }
    //
    //        String platformNotation = component.getId().getGroup() + ":_:2.0.0";
    //        log.error("Assigning component {} to virtual platform {}", component.getId(), platformNotation);
    //        component.belongsTo(platformNotation, true);
    //    }

    static final class ConsistentErrorPronePlatformRule implements ComponentMetadataRule {
        private static final String GROUP = "com.palantir";

        @Override
        public void execute(ComponentMetadataContext context) {
            ModuleVersionIdentifier id = context.getDetails().getId();

            System.out.println("BEFORE A");
            context.getDetails()
                    .allVariants(variant ->
                            variant.withDependencyConstraints(constraints -> constraints.forEach(constraint -> {
                                System.out.println(constraint.getAttributes());
                                if (VIRTUAL_PLATFORM_MODULE.equals(constraint.getName())) {
                                    System.out.println("ADDED PLATFORM RULE");
                                    context.getDetails().belongsTo("%s:_:%s".formatted(GROUP, id.getVersion()));
                                }
                            })));

            System.out.println("Aasdasd");

            //            System.out.println("HERE");
            //            System.out.println(context.getDetails());
            //
            //            if (!id.getGroup().equals(GROUP)) {
            //                return;
            //            }
            //
            //            context.getDetails().belongsTo("%s:_:%s".formatted(GROUP, id.getVersion()));
        }
    }
}
