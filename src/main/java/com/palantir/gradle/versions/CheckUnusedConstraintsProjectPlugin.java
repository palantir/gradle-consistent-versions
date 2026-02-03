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
import javax.inject.Inject;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.result.ResolutionResult;
import org.gradle.api.artifacts.result.ResolvedComponentResult;
import org.gradle.api.attributes.Usage;
import org.gradle.api.file.ProjectLayout;

public abstract class CheckUnusedConstraintsProjectPlugin implements Plugin<Project> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    static final String OUTGOING_USAGE = "check-unused-constraints-module-identifiers";

    @Inject
    protected abstract ProjectLayout getLayout();

    @Override
    public final void apply(Project project) {
        project.getConfigurations().register("check-unused-constraints-outgoing", outgoing -> {
            outgoing.setCanBeConsumed(true);
            outgoing.setCanBeResolved(false);
            outgoing.setVisible(false);
            outgoing.attributes(attrs -> {
                attrs.attribute(Usage.USAGE_ATTRIBUTE, project.getObjects().named(Usage.class, OUTGOING_USAGE));
            });

            outgoing.getOutgoing()
                    .artifact(getLayout()
                            .getBuildDirectory()
                            .file("check-unused-constraints/resolved-module-identifiers.json")
                            .map(regFile -> {
                                File file = regFile.getAsFile();
                                file.getParentFile().mkdirs();

                                Set<ResolvedModule> identifiers =
                                        GradleConfigurations.getResolvableConfigurations(project).stream()
                                                .flatMap(CheckUnusedConstraintsProjectPlugin::getResolvedModules)
                                                .collect(Collectors.toSet());

                                try {
                                    OBJECT_MAPPER.writeValue(file, identifiers);
                                } catch (IOException e) {
                                    throw new UncheckedIOException("Failed to write resolved module identifiers", e);
                                }
                                return file;
                            }));
        });
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
