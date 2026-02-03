/*
 * (c) Copyright 2026 Palantir Technologies Inc. All rights reserved.
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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.result.ResolutionResult;
import org.gradle.api.artifacts.result.ResolvedComponentResult;
import org.gradle.api.attributes.Usage;
import org.gradle.api.plugins.JavaPlugin;

public class CheckUnusedConstraintsProjectPlugin implements Plugin<Project> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    static final String OUTGOING_USAGE = "check-unused-constraints-module-identifiers";

    @Override
    public final void apply(Project project) {
        project.getPlugins().withType(JavaPlugin.class, _javaPlugin -> {
            createOutgoingConfiguration(project);
        });
    }

    private static void createOutgoingConfiguration(Project project) {
        project.getConfigurations().register("check-unused-constraints-outgoing", outgoing -> {
            outgoing.setCanBeConsumed(true);
            outgoing.setCanBeResolved(false);
            outgoing.setVisible(false);
            outgoing.attributes(attrs -> {
                attrs.attribute(Usage.USAGE_ATTRIBUTE, project.getObjects().named(Usage.class, OUTGOING_USAGE));
            });

            outgoing.getOutgoing().artifact(project.provider(() -> writeResolvedModuleIdentifiers(project)));
        });
    }

    static File writeResolvedModuleIdentifiers(Project project) {
        File outputFile = new File(
                project.getLayout().getBuildDirectory().getAsFile().get(),
                "check-unused-constraints/resolved-module-identifiers.json");
        outputFile.getParentFile().mkdirs();

        Set<ResolvedModule> identifiers = GradleConfigurations.getResolvableConfigurations(project).stream()
                .flatMap(CheckUnusedConstraintsProjectPlugin::getResolvedModules)
                .collect(Collectors.toSet());

        try {
            OBJECT_MAPPER.writeValue(outputFile, identifiers);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write resolved module identifiers", e);
        }
        return outputFile;
    }

    private static Stream<ResolvedModule> getResolvedModules(Configuration configuration) {
        ResolutionResult resolutionResult = configuration.getIncoming().getResolutionResult();
        return resolutionResult.getAllComponents().stream()
                .map(ResolvedComponentResult::getId)
                .filter(cid -> !cid.equals(resolutionResult.getRoot().getId()))
                .filter(ModuleComponentIdentifier.class::isInstance)
                .map(ModuleComponentIdentifier.class::cast)
                .map(mcid -> ResolvedModule.of(configuration.getName(), mcid.getGroup() + ":" + mcid.getModule()));
    }
}
