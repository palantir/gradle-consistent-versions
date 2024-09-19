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

package com.palantir.gradle.versions.intellij;

import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.openapi.components.ComponentManager;
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder;
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings;
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.vfs.AsyncFileListener;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.gradle.util.GradleConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class VersionPropsFileListener implements AsyncFileListener {
    private static final Logger log = LoggerFactory.getLogger(VersionPropsFileListener.class);

    public VersionPropsFileListener() {
        log.debug("Got created");
    }

    @Nullable
    @Override
    public ChangeApplier prepareChange(List<? extends VFileEvent> events) {
        List<VirtualFile> versionPropsEvents = events.stream()
                .filter(VFileEvent::isFromSave) // This is quite expensive and noisy so only run on save
                .map(VFileEvent::getFile)
                .filter(Objects::nonNull)
                .filter(file -> "versions.props".equals(file.getName()))
                .toList();

        if (versionPropsEvents.isEmpty()) {
            return null;
        }

        List<Project> projectsAffected = Arrays.stream(
                        ProjectManager.getInstance().getOpenProjects())
                .filter(Project::isInitialized)
                .filter(Predicate.not(ComponentManager::isDisposed))
                .filter(project -> versionPropsEvents.stream()
                        .anyMatch(event -> event.getPath().startsWith(project.getBasePath())))
                .toList();

        return new ChangeApplier() {
            @Override
            public void afterVfsChange() {
                projectsAffected.forEach(project -> {
                    ExternalSystemTaskExecutionSettings settings = new ExternalSystemTaskExecutionSettings();
                    settings.setExternalProjectPath(project.getBasePath());
                    settings.setTaskNames(Collections.singletonList("writeVersionsLock"));
                    settings.setExternalSystemIdString(GradleConstants.SYSTEM_ID.toString());

                    ExternalSystemUtil.runTask(
                            settings, DefaultRunExecutor.EXECUTOR_ID, project, GradleConstants.SYSTEM_ID);
                    ExternalSystemUtil.refreshProject(
                            project.getBasePath(), new ImportSpecBuilder(project, GradleConstants.SYSTEM_ID).build());
                });
            }
        };
    }
}
