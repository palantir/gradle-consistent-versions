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

package com.palantir.gradle.versions.ideapluginsettings;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import java.util.List;
import org.immutables.value.Value;
import org.jetbrains.annotations.Nullable;

@Value.Immutable
@JsonDeserialize(as = ImmutableOption.class)
@JsonSerialize(as = ImmutableOption.class)
public interface Option {

    @Value.Parameter
    @JacksonXmlProperty(isAttribute = true)
    String name();

    @Nullable
    @Value.Parameter
    @JacksonXmlProperty(isAttribute = true)
    String value();

    @Nullable
    @Value.Parameter
    @JacksonXmlElementWrapper(localName = "list")
    @JacksonXmlProperty(localName = "option")
    List<ListOption> listOptions();
}
