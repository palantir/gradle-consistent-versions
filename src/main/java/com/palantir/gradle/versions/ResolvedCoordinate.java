/*
 * (c) Copyright 2026 Palantir Technologies Inc. All rights reserved.
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

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.io.Serializable;
import java.util.Comparator;
import org.immutables.value.Value;

@Value.Immutable
@JsonSerialize(as = ImmutableResolvedCoordinate.class)
@JsonDeserialize(as = ImmutableResolvedCoordinate.class)
public interface ResolvedCoordinate extends Serializable, Comparable<ResolvedCoordinate> {
    Comparator<ResolvedCoordinate> COMPARATOR = Comparator.comparing(ResolvedCoordinate::configuration)
            .thenComparing(ResolvedCoordinate::module)
            .thenComparing(ResolvedCoordinate::group);

    String configuration();

    String group();

    String module();

    @Override
    default int compareTo(ResolvedCoordinate other) {
        return COMPARATOR.compare(this, other);
    }

    static ResolvedCoordinate of(String configuration, String group, String module) {
        return ImmutableResolvedCoordinate.builder()
                .configuration(configuration)
                .group(group)
                .module(module)
                .build();
    }
}
