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

package com.palantir.gradle.versions.intellij.psi;

import com.intellij.extapi.psi.PsiFileBase;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.psi.FileViewProvider;
import com.palantir.gradle.versions.intellij.VersionPropsFileType;
import com.palantir.gradle.versions.intellij.VersionPropsLanguage;
import org.jetbrains.annotations.NotNull;

public class VersionPropsFile extends PsiFileBase {

    public VersionPropsFile(@NotNull FileViewProvider viewProvider) {
        super(viewProvider, VersionPropsLanguage.INSTANCE);
    }

    @NotNull
    @Override
    public final FileType getFileType() {
        return VersionPropsFileType.INSTANCE;
    }

    @Override
    public final String toString() {
        return "VersionProps File";
    }
}