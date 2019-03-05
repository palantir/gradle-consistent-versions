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

import com.google.common.base.Splitter;
import com.google.common.collect.Iterables;
import java.io.File;
import java.util.Set;
import java.util.stream.Collectors;
import org.gradle.api.DefaultTask;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.result.ResolutionResult;
import org.gradle.api.file.RegularFile;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;
import org.gradle.util.VersionNumber;

public class CheckNoUnusedPinTask extends DefaultTask {

    private final Property<Boolean> shouldFix = getProject().getObjects().property(Boolean.class);
    private final RegularFileProperty propsFileProperty = getProject().getObjects().fileProperty();

    public CheckNoUnusedPinTask() {
        shouldFix.set(false);
        setDescription("Ensures all lines in your versions.props correspond to an actual gradle dependency");
    }

    final void setPropsFile(File propsFile) {
        this.propsFileProperty.set(propsFile);
    }

    @Input
    public final Set<String> getResolvedArtifacts() {
        return getAllProjectsResolvedModuleIdentifiers(getProject());
    }

    @InputFile
    public final Provider<RegularFile> getPropsFile() {
        return propsFileProperty;
    }

    @Option(option = "fix", description = "Whether to apply the suggested fix to versions.props")
    public final void setShouldFix(boolean shouldFix) {
        this.shouldFix.set(shouldFix);
    }

    final void setShouldFix(Provider<Boolean> shouldFix) {
        this.shouldFix.set(shouldFix);
    }

    @TaskAction
    public final void checkNoUnusedPin() {
        RawVersionsProps.ParsedVersionsProps parsedVersionsProps = RawVersionsProps.readVersionsProps(getPropsFile().get().getAsFile());

        Set<RawVersionsProps.VersionForce> downgrades = getResolvedArtifacts().stream().flatMap(artifact -> {
            // for a single artifact, we want to find any matching lines in versions.props which
            // are lower than the current version
            return parsedVersionsProps.forces().stream()
                    .filter(force -> artifact.matches(force.name().replaceAll("\\*", ".*")))
                    .filter(force -> {
                        VersionNumber forceVersion = VersionNumber.parse(force.version());
                        VersionNumber actualVersion = VersionNumber.parse(
                                Iterables.getLast(Splitter.on(':').split(artifact)));
                        boolean hasNoEffect = forceVersion.compareTo(actualVersion) < 0;
                        return hasNoEffect;
                    });
        }).collect(Collectors.toSet());

        if (downgrades.isEmpty()) {
            return;
        }

        String linesToDelete = downgrades.stream()
                .map(force -> String.format(" - '%s'", force.name()))
                .collect(Collectors.joining("\n"));

        if (shouldFix.get()) {
            getProject().getLogger().lifecycle("Removing unused pins from versions.props:\n"
                    + linesToDelete);
            RawVersionsProps.writeVersionsProps(
                    parsedVersionsProps, downgrades.stream(), getPropsFile().get().getAsFile());
            return;
        }

        throw new RuntimeException(
                "There are redundant lines in your versions.props: \n" + linesToDelete
                        + "\n\n"
                        + "Rerun with --fix to remove them.");
    }

    private static Set<String> getAllProjectsResolvedModuleIdentifiers(Project project) {
        return project.getRootProject().getAllprojects()
                .stream()
                .flatMap(project2 -> getResolvedModuleIdentifiers(project2).stream())
                .collect(Collectors.toSet());
    }

    /** Returns a `group:module:version` string for every dependency in all configurations in this project. */
    private static Set<String> getResolvedModuleIdentifiers(Project project) {
        return project.getConfigurations().stream()
                .filter(Configuration::isCanBeResolved)
                .flatMap(configuration -> {
                    try {
                        ResolutionResult resolutionResult = configuration.getIncoming().getResolutionResult();
                        return resolutionResult
                                .getAllComponents()
                                .stream()
                                .map(result -> result.getId())
                                .filter(cid -> !cid.equals(resolutionResult.getRoot().getId())) // remove the project
                                .filter(cid -> cid instanceof ModuleComponentIdentifier)
                                .map(mcid -> ((ModuleComponentIdentifier) mcid))
                                .map(mid -> mid.getGroup() + ":" + mid.getModule() + ":" + mid.getVersion());
                    } catch (Exception e) {
                        throw new RuntimeException(String.format("Error during resolution of the dependency graph of "
                                + "configuration %s", configuration), e);
                    }
                })
                .collect(Collectors.toSet());
    }
}
