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
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
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
        private static final ObjectMapper JSON_MAPPER = new ObjectMapper().registerModule(new Jdk8Module());
        private static final XmlMapper XML_MAPPER = new XmlMapper();

        static {
            XML_MAPPER.registerModule(new Jdk8Module());
        }

        @Inject
        protected abstract RepositoryResourceAccessor getRepositoryResourceAccessor();

        @Override
        public final void execute(ComponentMetadataContext context) {
            ModuleVersionIdentifier id = context.getDetails().getId();

            // Try GMM first
            String gmmPath = buildGmmPath(id);
            getRepositoryResourceAccessor().withResource(gmmPath, resource -> {
                parseMetadata(resource, JSON_MAPPER, GradleModuleMetadata.class)
                        .flatMap(GradleModuleMetadata::extractDependencies)
                        .filter(deps -> hasVirtualPlatform(deps, id.getGroup()))
                        .ifPresent(deps -> assignToPlatform(context, id));
            });

            // If GMM didn't work, try POM
            String pomPath = buildPomPath(id);
            getRepositoryResourceAccessor().withResource(pomPath, resource -> {
                parseMetadata(resource, XML_MAPPER, PomMetadata.class)
                        .flatMap(PomMetadata::extractDependencies)
                        .filter(deps -> hasVirtualPlatform(deps, id.getGroup()))
                        .ifPresent(deps -> assignToPlatform(context, id));
            });
        }

        private static <T> Optional<T> parseMetadata(
                InputStream resource, ObjectMapper mapper, Class<T> metadataClass) {
            try {
                return Optional.of(mapper.readValue(resource, metadataClass));
            } catch (IOException e) {
                log.debug("Failed to parse {} metadata: {}", metadataClass.getSimpleName(), e.getMessage());
                return Optional.empty();
            }
        }

        private static boolean hasVirtualPlatform(Stream<Dependency> dependencies, String expectedGroup) {
            return dependencies.anyMatch(dep -> isVirtualPlatformDependency(dep, expectedGroup));
        }

        private static boolean isVirtualPlatformDependency(Dependency dependency, String expectedGroup) {
            return dependency.group().filter(expectedGroup::equals).isPresent()
                    && dependency.module().filter(VIRTUAL_PLATFORM_NAME::equals).isPresent();
        }

        private static String buildGmmPath(ModuleVersionIdentifier id) {
            String groupPath = id.getGroup().replace('.', '/');
            return String.format(
                    "%s/%s/%s/%s-%s.module", groupPath, id.getName(), id.getVersion(), id.getName(), id.getVersion());
        }

        private static String buildPomPath(ModuleVersionIdentifier id) {
            String groupPath = id.getGroup().replace('.', '/');
            return String.format(
                    "%s/%s/%s/%s-%s.pom", groupPath, id.getName(), id.getVersion(), id.getName(), id.getVersion());
        }

        private static void assignToPlatform(ComponentMetadataContext context, ModuleVersionIdentifier id) {
            String platformNotation = id.getGroup() + ":_:" + id.getVersion();
            log.info("Assigning component {} to virtual platform {}", id, platformNotation);
            context.getDetails().belongsTo(platformNotation, true);
        }
    }

    interface MetadataWithDependencies {
        Optional<Stream<Dependency>> extractDependencies();
    }

    @Value.Immutable
    @JsonDeserialize(as = ImmutableDependency.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    interface Dependency {
        @JsonProperty("group")
        @JacksonXmlProperty(localName = "groupId")
        Optional<String> group();

        @JsonProperty("module")
        @JacksonXmlProperty(localName = "artifactId")
        Optional<String> module();
    }

    // GMM Models
    @Value.Immutable
    @JsonDeserialize(as = ImmutableGradleModuleMetadata.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    interface GradleModuleMetadata extends MetadataWithDependencies {
        @Value.Default
        default List<Variant> variants() {
            return List.of();
        }

        @Override
        default Optional<Stream<Dependency>> extractDependencies() {
            Stream<Dependency> deps = variants().stream().flatMap(variant -> variant.dependencyConstraints().stream());
            return Optional.of(deps);
        }
    }

    @Value.Immutable
    @JsonDeserialize(as = ImmutableVariant.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    interface Variant {
        @Value.Default
        default List<Dependency> dependencyConstraints() {
            return List.of();
        }
    }

    // POM Models
    @Value.Immutable
    @JsonDeserialize(as = ImmutablePomMetadata.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    interface PomMetadata extends MetadataWithDependencies {
        Optional<DependencyManagement> dependencyManagement();

        @Override
        default Optional<Stream<Dependency>> extractDependencies() {
            return dependencyManagement().map(dm -> dm.dependencies().stream());
        }
    }

    @Value.Immutable
    @JsonDeserialize(as = ImmutableDependencyManagement.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    interface DependencyManagement {
        @JacksonXmlElementWrapper(localName = "dependencies")
        @JacksonXmlProperty(localName = "dependency")
        @Value.Default
        default List<Dependency> dependencies() {
            return List.of();
        }
    }
}
