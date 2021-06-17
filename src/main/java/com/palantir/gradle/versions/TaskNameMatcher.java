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

import com.google.common.collect.Streams;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

final class TaskNameMatcher {
    private final List<String> fullTaskNameParts;

    TaskNameMatcher(String fullTaskName) {
        List<String> parts = toParts(fullTaskName);

        this.fullTaskNameParts = parts.stream().filter(part -> !part.isEmpty()).collect(Collectors.toList());
    }

    public boolean matches(String taskName) {
        List<String> taskNameParts = toParts(taskName);

        System.out.println("fullTaskNameParts = " + fullTaskNameParts);
        System.out.println("taskNameParts = " + taskNameParts);

        if (taskNameParts.size() != fullTaskNameParts.size()) {
            return false;
        }

        return Streams.zip(fullTaskNameParts.stream(), taskNameParts.stream(), String::startsWith)
                .allMatch(bool -> bool);
    }

    public boolean matchesAny(Collection<String> tasks) {
        return tasks.stream().allMatch(this::matches);
    }

    private static List<String> toParts(String fullTaskName) {
        List<String> parts = new ArrayList<>();
        StringBuilder currentPart = new StringBuilder();
        for (int i = 0; i < fullTaskName.length(); i++) {
            char chr = fullTaskName.charAt(i);
            if (Character.isUpperCase(chr)) {
                parts.add(currentPart.toString());
                currentPart.setLength(0);
            }
            currentPart.append(chr);
        }

        parts.add(currentPart.toString());

        return parts.stream().filter(string -> !string.isEmpty()).collect(Collectors.toList());
    }
}
