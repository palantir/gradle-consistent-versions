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

import com.ctc.wstx.stax.WstxInputFactory;
import com.ctc.wstx.stax.WstxOutputFactory;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
import java.util.stream.Collectors;
import javax.inject.Inject;
import org.gradle.api.Plugin;
import org.gradle.api.UncheckedIOException;
import org.gradle.api.artifacts.ComponentMetadataContext;
import org.gradle.api.artifacts.ComponentMetadataRule;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.repositories.RepositoryResourceAccessor;
import org.gradle.api.initialization.Settings;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.immutables.value.Value;

public class ExternallyConsistentVersionsSettingsPlugin implements Plugin<Settings> {

    private static final Logger log = Logging.getLogger(ExternallyConsistentVersionsSettingsPlugin.class);

    @Override
    public final void apply(Settings settings) {
        settings.getGradle().rootProject(rootProject -> {
            rootProject.getPluginManager().apply(ExternallyConsistentVersionsProducerPlugin.class);
        });

        settings.getGradle().allprojects(project -> {
            project.getDependencies().getComponents().all(VirtualPlatformRule.class);
            project.getBuildscript().getDependencies().getComponents().all(VirtualPlatformRule.class);
        });
    }

    public abstract static class VirtualPlatformRule implements ComponentMetadataRule {
        private static final ObjectMapper XML_MAPPER =
                new XmlMapper(new WstxInputFactory(), new WstxOutputFactory()).registerModule(new Jdk8Module());

        @Inject
        protected abstract RepositoryResourceAccessor getRepositoryResourceAccessor();

        @Override
        public final void execute(ComponentMetadataContext context) {
            ModuleVersionIdentifier id = context.getDetails().getId();

            String pomPath = buildPomPath(id);
            getRepositoryResourceAccessor().withResource(pomPath, resource -> {
                parsePom(resource)
                        .flatMap(Metadata::extractDependencies)
                        .map(VirtualPlatformRule::extractVirtualPlatforms)
                        .filter(platforms -> !platforms.isEmpty())
                        .ifPresent(platforms -> assignToPlatforms(context, id, platforms));
            });
        }

        private static Optional<Metadata> parsePom(InputStream resource) {
            try {
                return Optional.of(XML_MAPPER.readValue(resource, Metadata.class));
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to parse POM: ", e);
            }
        }

        private static List<String> extractVirtualPlatforms(List<Dependency> dependencies) {
            return dependencies.stream()
                    .map(VirtualPlatformRule::buildPlatformCoordinate)
                    .<String>mapMulti(Optional::ifPresent)
                    .collect(Collectors.toList());
        }

        private static Optional<String> buildPlatformCoordinate(Dependency dependency) {
            return dependency
                    .group()
                    .filter(group ->
                            group.startsWith(ExternallyConsistentVersionsProducerPlugin.VIRTUAL_PLATFORM_PREFIX))
                    .map(group -> group.substring(
                            ExternallyConsistentVersionsProducerPlugin.VIRTUAL_PLATFORM_PREFIX.length()))
                    .flatMap(extractedGroup -> dependency.module().map(module -> extractedGroup + ":" + module));
        }

        private static String buildPomPath(ModuleVersionIdentifier id) {
            String groupPath = id.getGroup().replace('.', '/');
            return String.format(
                    "%s/%s/%s/%s-%s.pom", groupPath, id.getName(), id.getVersion(), id.getName(), id.getVersion());
        }

        private static void assignToPlatforms(
                ComponentMetadataContext context, ModuleVersionIdentifier id, List<String> platformCoordinates) {
            platformCoordinates.forEach(platform -> {
                log.debug("Assigning component {} to virtual platform {}", id, platform);
                context.getDetails().belongsTo(platform + ":" + id.getVersion(), true);
            });
        }
    }

    @Value.Immutable
    @JsonDeserialize(as = ImmutableMetadata.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    interface Metadata {
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

    @Value.Immutable
    @JsonDeserialize(as = ImmutableDependency.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    interface Dependency {
        @JacksonXmlProperty(localName = "groupId")
        Optional<String> group();

        @JacksonXmlProperty(localName = "artifactId")
        Optional<String> module();

        @JacksonXmlProperty(localName = "version")
        Optional<String> version();
    }
}
