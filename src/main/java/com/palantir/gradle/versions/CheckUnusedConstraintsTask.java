/*
 * (c) Copyright 2018 Palantir Technologies Inc. All rights reserved.
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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import com.palantir.gradle.failurereports.exceptions.ExceptionWithSuggestion;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.gradle.api.DefaultTask;
import org.gradle.api.Project;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.result.ResolutionResult;
import org.gradle.api.artifacts.result.ResolvedComponentResult;
import org.gradle.api.file.RegularFile;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;
import org.gradle.language.base.plugins.LifecycleBasePlugin;

public class CheckUnusedConstraintsTask extends DefaultTask {

    private final Property<Boolean> shouldFailWithConfigurationOnDemandMessage =
            getProject().getObjects().property(Boolean.class);
    private final Property<Boolean> shouldFix = getProject().getObjects().property(Boolean.class);
    private final RegularFileProperty propsFileProperty =
            getProject().getObjects().fileProperty();
    private final SetProperty<String> classpath = getProject().getObjects().setProperty(String.class);

    public CheckUnusedConstraintsTask() {
        shouldFailWithConfigurationOnDemandMessage.set(false);
        shouldFix.set(false);
        setGroup(LifecycleBasePlugin.VERIFICATION_GROUP);
        setDescription("Ensures all versions in your versions.props correspond to an actual gradle dependency");
        getOutputs().upToDateWhen(_task -> true); // task has no outputs, this is need for it to be up to date
    }

    final void setPropsFile(File propsFile) {
        this.propsFileProperty.set(propsFile);
    }

    @Input
    public final SetProperty<String> getClasspath() {
        return classpath;
    }

    @InputFile
    public final Property<RegularFile> getPropsFile() {
        return propsFileProperty;
    }

    @Input
    public final Property<Boolean> getShouldFailWithConfigurationOnDemandMessage() {
        return shouldFailWithConfigurationOnDemandMessage;
    }

    final void setShouldFailWithConfigurationOnDemandMessage(boolean shouldFail) {
        this.shouldFailWithConfigurationOnDemandMessage.set(shouldFail);
    }

    @Input
    public final Property<Boolean> getShouldFix() {
        return shouldFix;
    }

    @Option(option = "fix", description = "Whether to apply the suggested fix to versions.props")
    public final void setShouldFix(boolean shouldFix) {
        this.shouldFix.set(shouldFix);
    }

    @TaskAction
    public final void checkNoUnusedPin() {
        if (shouldFailWithConfigurationOnDemandMessage.get()) {
            throw new ExceptionWithSuggestion(
                    "The gradle-consistent-versions checkUnusedConstraints task must have all projects "
                            + "configured to work accurately, but due to Gradle configuration-on-demand, not all "
                            + "projects were configured. Make your command work by including a task with no project "
                            + "name (such as `./gradlew build` vs. `./gradlew :build`) or use --no-configure-on-demand.",
                    "./gradlew build");
        }

        Set<String> artifacts = getClasspath().get();
        VersionsProps versionsProps =
                VersionsProps.loadFromFile(getPropsFile().get().getAsFile().toPath());

        Set<String> exactConstraints = versionsProps.getFuzzyResolver().exactMatches();
        Set<String> unusedConstraints = new HashSet<>(Sets.difference(exactConstraints, artifacts));
        Set<String> unmatchedArtifacts = new HashSet<>(Sets.difference(artifacts, exactConstraints));

        // assumes globs are sorted by specificity
        for (FuzzyPatternResolver.Glob glob : versionsProps.getFuzzyResolver().globs()) {
            if (!unmatchedArtifacts.removeIf(glob::matches)) {
                unusedConstraints.add(glob.getRawPattern());
            }
        }

        if (unusedConstraints.isEmpty()) {
            return;
        } else if (shouldFix.get()) {
            getProject()
                    .getLogger()
                    .lifecycle("Removing unused pins from versions.props:\n"
                            + unusedConstraints.stream()
                                    .map(name -> String.format(" - '%s'", name))
                                    .collect(Collectors.joining("\n")));
            writeVersionsProps(getPropsFile().get().getAsFile(), unusedConstraints);
            return;
        }

        throw new ExceptionWithSuggestion(
                "There are unused pins in your versions.props: \n" + unusedConstraints + "\n\n"
                        + "Run ./gradlew checkUnusedConstraints --fix to remove them.",
                "./gradlew checkUnusedConstraints --fix");
    }

    private static void writeVersionsProps(File propsFile, Set<String> unusedConstraints) {
        List<String> lines = readVersionsPropsLines(propsFile);
        try (BufferedWriter writer0 =
                        Files.newBufferedWriter(propsFile.toPath(), StandardOpenOption.TRUNCATE_EXISTING);
                PrintWriter writer = new PrintWriter(writer0)) {
            for (String line : lines) {
                if (unusedConstraints.stream().noneMatch(line::startsWith)) {
                    writer.println(line);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Error opening or creating " + propsFile.toPath(), e);
        }
    }

    private static List<String> readVersionsPropsLines(File propsFile) {
        try (Stream<String> lines = Files.lines(propsFile.toPath())) {
            return lines.collect(ImmutableList.toImmutableList());
        } catch (IOException e) {
            throw new RuntimeException("Error reading " + propsFile.toPath(), e);
        }
    }

    static Stream<String> getResolvedModuleIdentifiers(Project project, VersionRecommendationsExtension extension) {
        return GradleConfigurations.getResolvableConfigurations(project).stream()
                .filter(configuration -> !extension.shouldExcludeConfiguration(configuration.getName()))
                .flatMap(configuration -> {
                    try {
                        ResolutionResult resolutionResult =
                                configuration.getIncoming().getResolutionResult();
                        return resolutionResult.getAllComponents().stream()
                                .map(ResolvedComponentResult::getId)
                                .filter(cid ->
                                        !cid.equals(resolutionResult.getRoot().getId())) // remove the project
                                .filter(cid -> cid instanceof ModuleComponentIdentifier)
                                .map(mcid -> ((ModuleComponentIdentifier) mcid).getModuleIdentifier())
                                .map(mid -> mid.getGroup() + ":" + mid.getName());
                    } catch (Exception e) {
                        throw new RuntimeException(
                                String.format(
                                        "Error during resolution of the dependency graph of configuration %s",
                                        configuration),
                                e);
                    }
                });
    }
}
