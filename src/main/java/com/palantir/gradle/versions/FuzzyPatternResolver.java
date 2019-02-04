/*
 * (c) Copyright 2019 Palantir Technologies Inc. All rights reserved.
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

import com.google.common.collect.ImmutableSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import org.immutables.value.Value;

/**
 * Adapted from {@code nebula.dependency-recommender}.
 */
@Value.Immutable
@SuppressWarnings("checkstyle:DesignForExtension")
public abstract class FuzzyPatternResolver {
    protected FuzzyPatternResolver() {}

    abstract Map<String, String> versions();

    @Value.Derived
    public Set<String> exactMatches() {
        return versions().keySet().stream().filter(name -> !name.contains("*")).collect(ImmutableSet.toImmutableSet());
    }

    @Value.Derived
    protected List<Glob> globs() {
        List<Glob> cache = new ArrayList<>();
        for (String name : versions().keySet()) {
            if (name.contains("*")) {
                cache.add(Glob.compile(name));
            }
        }
        // Sorting in order to prefer more specific globs (with more non-* characters), see the Glob class below.
        // The more specific globs will end up at the beginning of the array.
        Collections.sort(cache);
        return cache;
    }

    @Nullable
    public final String patternFor(String key) {
        // Always prefer exact matches (which should be handled separately).
        if (exactMatches().contains(key)) {
            return null;
        }

        for (Glob glob : globs()) {
            if (glob.matches(key)) {
                return glob.rawPattern;
            }
        }

        return null;
    }

    public static class Builder extends ImmutableFuzzyPatternResolver.Builder {}

    public static Builder builder() {
        return new Builder();
    }

    protected static final class Glob implements Comparable<Glob> {
        private final Pattern pattern;
        private final String rawPattern;
        private final int weight;

        private Glob(Pattern pattern, String rawPattern, int weight) {
            this.pattern = pattern;
            this.rawPattern = rawPattern;
            this.weight = weight;
        }

        private static Glob compile(String glob) {
            StringBuilder patternBuilder = new StringBuilder();
            boolean first = true;
            int weight = 0;

            for (String token : glob.split("\\*", -1)) {
                if (first) {
                    first = false;
                } else {
                    patternBuilder.append(".*?");
                }

                weight += token.length();
                patternBuilder.append(Pattern.quote(token));
            }

            Pattern pattern = Pattern.compile(patternBuilder.toString());

            return new Glob(pattern, glob, weight);
        }

        private boolean matches(String key) {
            return pattern.matcher(key).matches();
        }

        @Override
        public int compareTo(Glob other) {
            return Integer.compare(other.weight, weight);
        }
    }
}
