/*
 * (c) Copyright 2020 Palantir Technologies Inc. All rights reserved.
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

import com.google.common.base.Suppliers;
import java.util.function.Supplier;

/**
 * A way to represent a lazily evaluated string, to use with code like {@link
 * org.gradle.internal.typeconversion.MapNotationConverter} which always calls {@link Object#toString()} on the map
 * values.
 */
final class LazyString {
    private final Supplier<String> supplier;

    LazyString(Supplier<String> supplier) {
        this.supplier = Suppliers.memoize(supplier::get);
    }

    @Override
    public String toString() {
        return supplier.get();
    }
}
