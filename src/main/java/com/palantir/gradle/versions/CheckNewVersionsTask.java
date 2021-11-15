/*
 * (c) Copyright 2021 Palantir Technologies Inc. All rights reserved.
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.gradle.api.DefaultTask;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.LenientConfiguration;
import org.gradle.api.artifacts.ResolvedDependency;
import org.gradle.api.specs.Specs;
import org.gradle.api.tasks.TaskAction;

public class CheckNewVersionsTask extends DefaultTask {

    @TaskAction
    public void taskAction() {
        getProject().getConfigurations().forEach(config -> printLatestVersions(config, config.getName()));

        // Suggest upgrades for plugins
        Configuration pluginConfiguration = getProject().getBuildscript().getConfigurations().getByName("classpath");
        printLatestVersions(pluginConfiguration, "<plugins>");
    }

    private void printLatestVersions(Configuration config, String configurationName) {
        Configuration resolvableConfig = config.copyRecursive().setTransitive(false);
        resolvableConfig.setCanBeResolved(true);
        LenientConfiguration origLenient = resolvableConfig.getResolvedConfiguration().getLenientConfiguration();
        Set<ResolvedDependency> originalDeps = origLenient.getFirstLevelModuleDependencies(Specs.SATISFIES_ALL);

        Map<String, String> depToCurrentVersion = new HashMap<>();

        Set<Dependency> latestDepsForConfig = originalDeps.stream().map(dep -> {
                    String key = dep.getModuleGroup() + ":" + dep.getModuleName();
                    depToCurrentVersion.put(key, dep.getModuleVersion());
                    return getProject().getDependencies().create(key + ":+");
                })
                .collect(Collectors.toSet());

        Configuration copy = config.copyRecursive().setTransitive(false);
        copy.setCanBeResolved(true);
        copy.getDependencies().clear();
        // TODO(markelliot): we may want to find a way to tweak the resolution strategy so that forced module overrides
        //  still get a recommended upgrade

        copy.getDependencies().addAll(latestDepsForConfig);

        LenientConfiguration lenient =
                copy.getResolvedConfiguration().getLenientConfiguration();

        Set<ResolvedDependency> resolvedDeps =
                lenient.getFirstLevelModuleDependencies(Specs.SATISFIES_ALL);

        List<String> upgradeRecs = resolvedDeps.stream().flatMap(recDep -> {
            String key = recDep.getModuleGroup() + ":" + recDep.getModuleName();
            String currentVersion = depToCurrentVersion.get(key);
            if (recDep.getModuleVersion().equals(currentVersion)) {
                return Stream.empty();
            }
            return Stream.of(key
                    + " "
                    + currentVersion
                    + " -> "
                    + recDep.getModuleVersion());
        }).collect(Collectors.toList());

        if (!upgradeRecs.isEmpty()) {
            System.out.println("Dependency upgrades for project '"
                    + getProject().getName()
                    + "' configuration '"
                    + configurationName + "':");
            upgradeRecs.forEach(rec -> System.out.println(" - " + rec));
        }
    }
}
