/*
 * (c) Copyright 2024 Palantir Technologies Inc. All rights reserved.
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

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RequireConsistentVersionsIdeaPlugin implements Plugin<Project> {
    private static final Logger log = LoggerFactory.getLogger(RequireConsistentVersionsIdeaPlugin.class);
    private static final String MIN_IDEA_PLUGIN_VERSION = "0.9.0";

    @Override
    public final void apply(Project project) {

        if (!Boolean.getBoolean("idea.active")) {
            return;
        }

        project.getPluginManager().withPlugin("idea", _ideaPlugin -> {
            configureIntelliJImport(project);
        });
    }

    private static void configureIntelliJImport(Project project) {
        // Note: we tried using 'org.jetbrains.gradle.plugin.idea-ext' and afterSync triggers, but these are currently
        // very hard to manage as the tasks feel disconnected from the Sync operation, and you can't remove them once
        // you've added them. For that reason, we accept that we have to resolve this configuration at
        // configuration-time, but only do it when part of an IDEA import.
        project.getGradle().projectsEvaluated(_gradle -> {
            ConfigureIdeaPluginXml.updateIdeaXmlFile(
                    project.file(".idea/externalDependencies.xml"), MIN_IDEA_PLUGIN_VERSION, true);

            // Still configure legacy idea if using intellij import
            ConfigureIdeaPluginXml.updateIdeaXmlFile(
                    project.file(project.getName() + ".ipr"), MIN_IDEA_PLUGIN_VERSION, false);
        });
    }
}
