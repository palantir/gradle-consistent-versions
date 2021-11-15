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
        Configuration pluginConfiguration =
                getProject().getBuildscript().getConfigurations().getByName("classpath");
        printLatestVersions(pluginConfiguration, "<plugins>");
    }

    private void printLatestVersions(Configuration config, String configurationName) {
        Configuration resolvableOriginal = getResolvableCopy(config);
        Map<String, ResolvedDependency> currentVersions = getResolvedVersions(resolvableOriginal);

        Configuration resolvableLatest = getResolvableCopy(config);
        Set<Dependency> latestDepsForConfig = currentVersions.keySet().stream()
                .map(key -> getProject().getDependencies().create(key + ":+"))
                .collect(Collectors.toSet());
        resolvableLatest.getDependencies().clear();
        resolvableLatest.getDependencies().addAll(latestDepsForConfig);
        // TODO(markelliot): we may want to find a way to tweak the resolution strategy so that forced module overrides
        //  still get a recommended upgrade

        Map<String, ResolvedDependency> latestVersions = getResolvedVersions(resolvableLatest);
        List<String> upgradeRecs = currentVersions.entrySet().stream()
                .flatMap(entry -> {
                    if (!latestVersions.containsKey(entry.getKey())) {
                        // possible to reach here because of project dependencies or unresolvable new versions
                        return Stream.empty();
                    }
                    String currentVersion = entry.getValue().getModuleVersion();
                    String latestVersion = latestVersions.get(entry.getKey()).getModuleVersion();
                    if (currentVersion.equals(latestVersion)) {
                        return Stream.empty();
                    }
                    return Stream.of(entry.getKey() + " " + currentVersion + " -> " + latestVersion);
                })
                .collect(Collectors.toList());

        if (!upgradeRecs.isEmpty()) {
            System.out.println("Dependency upgrades for project '"
                    + getProject().getName()
                    + "' configuration '"
                    + configurationName + "':");
            upgradeRecs.forEach(rec -> System.out.println(" - " + rec));
        }
    }

    private Configuration getResolvableCopy(Configuration config) {
        Configuration resolvableConfig = config.copyRecursive().setTransitive(false);
        resolvableConfig.setCanBeResolved(true);
        return resolvableConfig;
    }

    private Map<String, ResolvedDependency> getResolvedVersions(Configuration config) {
        LenientConfiguration lenientConfig = config.getResolvedConfiguration().getLenientConfiguration();
        Set<ResolvedDependency> moduleDeps = lenientConfig.getFirstLevelModuleDependencies(Specs.SATISFIES_ALL);
        Map<String, ResolvedDependency> resolvedDeps = new HashMap<>();
        moduleDeps.forEach(dep -> resolvedDeps.put(dep.getModuleGroup() + ":" + dep.getModuleName(), dep));
        return resolvedDeps;
    }
}
