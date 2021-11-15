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
import org.immutables.value.Value;

public class CheckNewVersionsTask extends DefaultTask {
    private boolean collapseConfigurations = true;

    @TaskAction
    public void taskAction() {
        Map<String, Set<VersionUpgradeDetail>> upgradeRecs = getProject().getConfigurations().stream()
                // make safe for use with gradle-consistent-versions
                .filter(config -> !config.getName().startsWith("consistentVersions"))
                .collect(Collectors.toMap(Configuration::getName, this::getUpgradesForConfiguration));

        if (upgradeRecs.values().stream().anyMatch(upgrades -> !upgrades.isEmpty())) {
            System.out.println(
                    "Dependency upgrades available for project '" + getProject().getName() + "'");
            if (collapseConfigurations) {
                upgradeRecs.values().stream()
                        .flatMap(Set::stream)
                        .collect(Collectors.toSet())
                        .forEach(upgrade -> System.out.println("- " + upgrade.asString()));
            } else {
                upgradeRecs.forEach((config, upgrades) -> {
                    if (!upgrades.isEmpty()) {
                        System.out.println(" * Configuration '" + config + "':");
                        upgrades.forEach(upgrade -> System.out.println("   - " + upgrade.asString()));
                    }
                });
            }
        }

        // Suggest upgrades for plugins
        Configuration pluginConfiguration =
                getProject().getBuildscript().getConfigurations().getByName("classpath");
        Set<VersionUpgradeDetail> pluginUpgrades = getUpgradesForConfiguration(pluginConfiguration);
        if (!pluginUpgrades.isEmpty()) {
            System.out.println(
                    "Plugin upgrades available for project '" + getProject().getName() + "'");
            pluginUpgrades.forEach(upgrade -> System.out.println("- " + upgrade.asString()));
        }
    }

    private Set<VersionUpgradeDetail> getUpgradesForConfiguration(Configuration config) {
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

        return currentVersions.entrySet().stream()
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
                    return Stream.of(ImmutableVersionUpgradeDetail.builder()
                            .group(entry.getValue().getModuleGroup())
                            .name(entry.getValue().getModuleName())
                            .currentVersion(currentVersion)
                            .latestVersion(latestVersion)
                            .build());
                })
                .collect(Collectors.toSet());
    }

    private Configuration getResolvableCopy(Configuration config) {
        Configuration resolvableConfig = config.copy().setTransitive(false);
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

    @Value.Immutable
    interface VersionUpgradeDetail {
        String group();

        String name();

        String currentVersion();

        String latestVersion();

        default String asString() {
            return group() + ":" + name() + ":{" + currentVersion() + " -> " + latestVersion() + "}";
        }
    }
}
