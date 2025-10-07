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
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
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
        private static final XmlMapper XML_MAPPER = new XmlMapper();

        static {
            XML_MAPPER.registerModule(new Jdk8Module());
        }

        @Inject
        protected abstract RepositoryResourceAccessor getRepositoryResourceAccessor();

        @Override
        public final void execute(ComponentMetadataContext context) {
            ModuleVersionIdentifier id = context.getDetails().getId();

            String pomPath = buildPomPath(id);
            getRepositoryResourceAccessor().withResource(pomPath, resource -> {
                parsePomMetadata(resource)
                        .flatMap(PomMetadata::extractDependencies)
                        .filter(deps -> hasVirtualPlatform(deps, id.getGroup()))
                        .ifPresent(_deps -> assignToPlatform(context, id));
            });
        }

        private static Optional<PomMetadata> parsePomMetadata(InputStream resource) {
            try {
                return Optional.of(XML_MAPPER.readValue(resource, PomMetadata.class));
            } catch (IOException e) {
                log.debug("Failed to parse POM metadata: {}", e.getMessage());
                return Optional.empty();
            }
        }

        private static boolean hasVirtualPlatform(List<Dependency> dependencies, String expectedGroup) {
            return dependencies.stream().anyMatch(dep -> isVirtualPlatformDependency(dep, expectedGroup));
        }

        private static boolean isVirtualPlatformDependency(Dependency dependency, String expectedGroup) {
            return dependency.group().filter(expectedGroup::equals).isPresent()
                    && dependency.module().filter(VIRTUAL_PLATFORM_NAME::equals).isPresent();
        }

        private static String buildPomPath(ModuleVersionIdentifier id) {
            String groupPath = id.getGroup().replace('.', '/');
            return String.format(
                    "%s/%s/%s/%s-%s.pom", groupPath, id.getName(), id.getVersion(), id.getName(), id.getVersion());
        }

        private static void assignToPlatform(ComponentMetadataContext context, ModuleVersionIdentifier id) {
            String platformNotation = id.getGroup() + ":_:" + id.getVersion();
            log.debug("Assigning component {} to virtual platform {}", id, platformNotation);
            context.getDetails().belongsTo(platformNotation, true);
        }
    }

    @Value.Immutable
    @JsonDeserialize(as = ImmutableDependency.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    interface Dependency {
        @JacksonXmlProperty(localName = "groupId")
        Optional<String> group();

        @JacksonXmlProperty(localName = "artifactId")
        Optional<String> module();
    }

    @Value.Immutable
    @JsonDeserialize(as = ImmutablePomMetadata.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    interface PomMetadata {
        Optional<DependencyManagement> dependencyManagement();

        default Optional<List<Dependency>> extractDependencies() {
            return dependencyManagement().map(DependencyManagement::dependencies);
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
