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

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.gradle.api.Plugin;
import org.gradle.api.artifacts.ComponentMetadataDetails;
import org.gradle.api.artifacts.DependencyConstraintMetadata;
import org.gradle.api.initialization.Settings;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;

public class VirtualPlatformSettingsPlugin implements Plugin<Settings> {

    private static final Logger log = Logging.getLogger(VirtualPlatformSettingsPlugin.class);
    private static final String VIRTUAL_PLATFORM_MODULE = "palantir-virtual-platform";

    private final Map<String, String> discoveredPlatforms = new ConcurrentHashMap<>();

    @Override
    public final void apply(Settings settings) {
        settings.getGradle().allprojects(project -> {
            log.debug("Configuring virtual platform rules for project {}", project.getPath());
            project.getBuildscript().getDependencies().getComponents().all(this::discoverPlatform);
            project.getDependencies().getComponents().all(this::discoverPlatform);
        });

        // After all projects are evaluated, configure buildscript resolutionStrategy
        settings.getGradle().projectsEvaluated(gradle -> {
            gradle.allprojects(project -> {
                project.getBuildscript().getDependencies().getComponents().all(this::tryAssignComponentToPlatform);
                project.getDependencies().getComponents().all(this::tryAssignComponentToPlatform);
            });
        });
    }

    private void discoverPlatform(ComponentMetadataDetails component) {
        component.allVariants(variant -> variant.withDependencyConstraints(
                constraints -> constraints.forEach(constraint -> discoverPlatform(component, constraint))));
    }

    private void discoverPlatform(ComponentMetadataDetails component, DependencyConstraintMetadata constraint) {

        if (!VIRTUAL_PLATFORM_MODULE.equals(constraint.getName())) {
            return;
        }

        String group = constraint.getGroup();
        String version = constraint.getVersionConstraint().getRequiredVersion();

        if (group.isEmpty() || version.isEmpty()) {
            return;
        }

        log.debug(
                "Found virtual platform marker in {}: {}:{}:{}",
                component.getId(),
                group,
                VIRTUAL_PLATFORM_MODULE,
                version);

        // Keep the highest version per group (lexicographically, for simplicity)
        discoveredPlatforms.merge(group, version, (oldV, newV) -> (compareVersions(oldV, newV) < 0) ? newV : oldV);
    }

    private void tryAssignComponentToPlatform(ComponentMetadataDetails component) {
        String componentGroup = component.getId().getGroup();
        Optional.ofNullable(discoveredPlatforms.get(componentGroup)).ifPresent(version -> {
            String platformNotation = componentGroup + ":_:" + version;
            log.debug("Assigning component {} to virtual platform {}", component.getId(), platformNotation);
            component.belongsTo(platformNotation);
        });
    }

    private static int compareVersions(String v1, String v2) {
        return v1.compareTo(v2);
    }
}
