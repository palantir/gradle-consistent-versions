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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import javax.inject.Inject;
import org.gradle.api.Plugin;
import org.gradle.api.artifacts.ComponentMetadataContext;
import org.gradle.api.artifacts.ComponentMetadataRule;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.repositories.RepositoryResourceAccessor;
import org.gradle.api.initialization.Settings;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.immutables.value.Value;

public class VirtualPlatformSettingsPlugin implements Plugin<Settings> {

    private static final Logger log = Logging.getLogger(VirtualPlatformSettingsPlugin.class);

    @Override
    public final void apply(Settings settings) {
        settings.getGradle().allprojects(project -> {
            project.getDependencies().getComponents().all(VirtualPlatformRule.class);
            project.getBuildscript().getDependencies().getComponents().all(VirtualPlatformRule.class);
        });
    }

    public abstract static class VirtualPlatformRule implements ComponentMetadataRule {
        private static final String VIRTUAL_PLATFORM_NAME = "palantir-virtual-platform";
        private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new Jdk8Module());

        @Inject
        protected abstract RepositoryResourceAccessor getRepositoryResourceAccessor();

        @Override
        public final void execute(ComponentMetadataContext context) {
            ModuleVersionIdentifier id = context.getDetails().getId();
            String metadataPath = buildMetadataPath(id);

            getRepositoryResourceAccessor().withResource(metadataPath, resource -> {
                parseMetadata(resource)
                        .filter(metadata -> hasVirtualPlatformConstraint(metadata, id.getGroup()))
                        .ifPresent(metadata -> assignToPlatform(context, id));
            });
        }

        private static Optional<GradleModuleMetadata> parseMetadata(InputStream resource) {
            try {
                return Optional.of(MAPPER.readValue(resource, GradleModuleMetadata.class));
            } catch (IOException e) {
                log.debug("Failed to parse metadata: {}", e.getMessage());
                return Optional.empty();
            }
        }

        private static boolean hasVirtualPlatformConstraint(GradleModuleMetadata metadata, String expectedGroup) {
            return metadata.variants().stream()
                    .flatMap(variant -> variant.dependencyConstraints().stream())
                    .anyMatch(constraint -> isVirtualPlatformConstraint(constraint, expectedGroup));
        }

        private static boolean isVirtualPlatformConstraint(DependencyConstraint constraint, String expectedGroup) {
            return constraint.group().filter(expectedGroup::equals).isPresent()
                    && constraint.module().filter(VIRTUAL_PLATFORM_NAME::equals).isPresent();
        }

        private static String buildMetadataPath(ModuleVersionIdentifier id) {
            String groupPath = id.getGroup().replace('.', '/');
            return String.format(
                    "%s/%s/%s/%s-%s.module", groupPath, id.getName(), id.getVersion(), id.getName(), id.getVersion());
        }

        private static void assignToPlatform(ComponentMetadataContext context, ModuleVersionIdentifier id) {
            String platformNotation = id.getGroup() + ":_:" + id.getVersion();
            log.info("Assigning component {} to virtual platform {}", id, platformNotation);
            context.getDetails().belongsTo(platformNotation, true);
        }
    }

    @Value.Immutable
    @JsonDeserialize(as = ImmutableGradleModuleMetadata.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    interface GradleModuleMetadata {
        @Value.Default
        default List<Variant> variants() {
            return List.of();
        }
    }

    @Value.Immutable
    @JsonDeserialize(as = ImmutableVariant.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    interface Variant {
        @Value.Default
        default List<DependencyConstraint> dependencyConstraints() {
            return List.of();
        }
    }

    @Value.Immutable
    @JsonDeserialize(as = ImmutableDependencyConstraint.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    interface DependencyConstraint {
        Optional<String> group();

        Optional<String> module();
    }
}
