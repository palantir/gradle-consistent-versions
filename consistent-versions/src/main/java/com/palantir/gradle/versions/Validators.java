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

import com.google.common.base.Strings;
import com.palantir.gradle.failurereports.exceptions.ExceptionWithSuggestion;
import java.nio.file.Path;

public final class Validators {

    public static void checkResultOrThrow(boolean condition, String errorTemplate, String commandSuggestion) {
        if (!condition) {
            throw new ExceptionWithSuggestion(
                    Strings.lenientFormat(errorTemplate, commandSuggestion), commandSuggestion);
        }
    }

    public static void checkResultOrThrow(boolean result, String errorMessage, Path filePath) {
        if (!result) {
            throw new ExceptionWithSuggestion(
                    errorMessage, filePath.getFileName().toString());
        }
    }

    public static void checkResultOrThrow(boolean result, String errorMessage, Path filePath, int lineNumber) {
        if (!result) {
            throw new ExceptionWithSuggestion(errorMessage, getInvalidFileLine(filePath, lineNumber));
        }
    }

    private static String getInvalidFileLine(Path filePath, int lineNumber) {
        return String.format("%s:%d", filePath.getFileName().toString(), lineNumber);
    }

    private Validators() {}
}
