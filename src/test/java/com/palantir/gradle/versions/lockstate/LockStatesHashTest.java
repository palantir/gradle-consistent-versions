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

package com.palantir.gradle.versions.lockstate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableSet;
import com.palantir.gradle.versions.GradleComparators;
import com.palantir.gradle.versions.internal.MyModuleVersionIdentifier;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import org.gradle.api.artifacts.VersionConstraint;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Characterisation tests for the per-line {@code (N constraints: HASH)} suffix produced by
 * {@link LockStates#computeLines}. The count and hash exist to make semantic merge conflicts (in
 * the presence of stale PR builds) surface as textual conflicts in {@code versions.lock}.
 *
 * <p>Two groups of tests:
 * <ul>
 *   <li>{@link Invariants}: properties any reasonable choice of "what feeds the hash" must
 *       satisfy. These should keep passing through intentional reshapes of the hash input
 *       (e.g. dropping requested-range bytes, or filtering out constraint-only dependents).
 *   <li>{@link CurrentBehaviour}: properties of the current implementation that a proposed
 *       simplification might intentionally relax. When the hash input changes, expect
 *       assertions here to flip — that is the point of having them pinned down.
 * </ul>
 */
class LockStatesHashTest {

    private static final MyModuleVersionIdentifier TARGET = MyModuleVersionIdentifier.of("com.target", "foo", "1.0.0");

    @Nested
    class Invariants {

        @Test
        void identicalDependentsProduceIdenticalHashes() {
            String hashA = hashFor(deps(builder().module("com.foo:bar", "1.0").build()));
            String hashB = hashFor(deps(builder().module("com.foo:bar", "1.0").build()));
            assertThat(hashA).isEqualTo(hashB);
        }

        @Test
        void countMatchesNumberOfDistinctRequesterEntries() {
            int count = numDependentsFor(deps(builder()
                    .module("com.foo:bar", "1.0")
                    .module("com.foo:baz", "1.0")
                    .build()));
            assertThat(count).isEqualTo(2);
        }

        @Test
        void addingARequesterChangesTheHash() {
            String before = hashFor(deps(builder().module("com.foo:bar", "1.0").build()));
            String after = hashFor(deps(builder()
                    .module("com.foo:bar", "1.0")
                    .module("com.foo:baz", "1.0")
                    .build()));
            assertThat(after).isNotEqualTo(before);
        }

        @Test
        void removingARequesterChangesTheHash() {
            String before = hashFor(deps(builder()
                    .module("com.foo:bar", "1.0")
                    .module("com.foo:baz", "1.0")
                    .build()));
            String after = hashFor(deps(builder().module("com.foo:bar", "1.0").build()));
            assertThat(after).isNotEqualTo(before);
        }

        @Test
        void swappingARequestersIdentityChangesTheHash() {
            String before = hashFor(deps(builder().module("com.foo:bar", "1.0").build()));
            String after = hashFor(deps(builder().module("com.foo:quux", "1.0").build()));
            assertThat(after)
                    .as("identity swap with unchanged count/range must still produce a textual change")
                    .isNotEqualTo(before);
        }

        @Test
        void addingARequesterChangesTheCount() {
            int before = numDependentsFor(deps(builder().module("com.foo:bar", "1.0").build()));
            int after = numDependentsFor(deps(builder()
                    .module("com.foo:bar", "1.0")
                    .module("com.foo:baz", "1.0")
                    .build()));
            assertThat(after).isGreaterThan(before);
        }
    }

    /**
     * Behaviours of the current implementation that future simplifications may intentionally
     * relax. Each assertion documents one feature of the current hash input.
     */
    @Nested
    class CurrentBehaviour {

        @Test
        void changingARequestersRequestedRangeChangesTheHash() {
            String before = hashFor(deps(builder().module("com.foo:bar", "1.0").build()));
            String after = hashFor(deps(builder().module("com.foo:bar", "[1.0,2.0)").build()));
            assertThat(after)
                    .as("today the hash includes the requested range — a range-only change perturbs"
                            + " every transitive line. Dropping range bytes from the hash would"
                            + " flip this assertion to isEqualTo.")
                    .isNotEqualTo(before);
        }

        @Test
        void changingARequestersRangeDoesNotChangeTheCount() {
            int before = numDependentsFor(deps(builder().module("com.foo:bar", "1.0").build()));
            int after = numDependentsFor(deps(builder().module("com.foo:bar", "[1.0,2.0)").build()));
            assertThat(after).isEqualTo(before);
        }

        @Test
        void allProjectRequestersCollapseToASingleEntry() {
            int count = numDependentsFor(deps(builder()
                    .project(":foo", "1.0")
                    .project(":bar", "1.0")
                    .project(":baz", "1.0")
                    .build()));
            assertThat(count)
                    .as("projects share a single 'projects' bucket in prettyPrintConstraints, so"
                            + " they collapse to one entry regardless of how many sub-projects request")
                    .isEqualTo(1);
        }

        @Test
        void addingAProjectRequesterAlongsideAnExistingExternalRequesterAddsOneEntry() {
            int before = numDependentsFor(deps(builder().module("com.foo:bar", "1.0").build()));
            int after = numDependentsFor(deps(builder()
                    .module("com.foo:bar", "1.0")
                    .project(":foo", "1.0")
                    .build()));
            assertThat(after).isEqualTo(before + 1);
        }

        @Test
        void swappingOneProjectRequesterForAnotherDoesNotChangeTheHash() {
            String before = hashFor(deps(builder().project(":foo", "1.0").build()));
            String after = hashFor(deps(builder().project(":bar", "1.0").build()));
            assertThat(after)
                    .as("the 'projects' bucket key is a constant string — swapping which project"
                            + " requests does not currently change the hash. A future change might"
                            + " include per-project identities here.")
                    .isEqualTo(before);
        }

        @Test
        void changingAProjectRequestersRangeChangesTheHash() {
            String before = hashFor(deps(builder().project(":foo", "1.0").build()));
            String after = hashFor(deps(builder().project(":foo", "[1.0,2.0)").build()));
            assertThat(after)
                    .as("project requesters' constraints feed the hash today via the 'projects ->'"
                            + " bucket, so changing a project's range perturbs the hash even though"
                            + " project identity is collapsed.")
                    .isNotEqualTo(before);
        }
    }

    /**
     * Scenario tests that mirror the merge cases discussed in the design analysis. They do not
     * exercise git itself — they just assert what the textual line content would be under each
     * branch's hypothetical resolution, which is the input git's 3-way merge would see.
     */
    @Nested
    class MergeScenarios {

        @Test
        void scenarioA_addVsDelete_branchAndAncestorLinesDifferTextually() {
            // ancestor: 1 requester p. Branch: 2 requesters {p, q}. Main: line deleted.
            // Whether the merge surfaces a conflict depends on whether the branch's line text
            // differs from the ancestor's — which is exactly what the count/hash gives us.
            int ancestorCount = numDependentsFor(deps(builder().module("p:p", "1.0").build()));
            String ancestorHash = hashFor(deps(builder().module("p:p", "1.0").build()));
            int branchCount = numDependentsFor(deps(builder()
                    .module("p:p", "1.0")
                    .module("q:q", "1.0")
                    .build()));
            String branchHash = hashFor(deps(builder()
                    .module("p:p", "1.0")
                    .module("q:q", "1.0")
                    .build()));
            assertThat(branchCount).as("count must change to drive textual conflict").isNotEqualTo(ancestorCount);
            assertThat(branchHash).as("hash must change to drive textual conflict").isNotEqualTo(ancestorHash);
        }

        @Test
        void scenarioB_swapVsDelete_branchAndAncestorLinesDifferTextually() {
            // ancestor: {p}. Branch: {p'} (swap, count unchanged). Main: deleted.
            // Without the hash (count only), branch's line would be textually identical to
            // ancestor's, and git would silently take main's delete. The hash protects this.
            int ancestorCount = numDependentsFor(deps(builder().module("p:p", "1.0").build()));
            String ancestorHash = hashFor(deps(builder().module("p:p", "1.0").build()));
            int branchCount = numDependentsFor(deps(builder().module("p:prime", "1.0").build()));
            String branchHash = hashFor(deps(builder().module("p:prime", "1.0").build()));
            assertThat(branchCount)
                    .as("count alone would not detect this — swap leaves count unchanged")
                    .isEqualTo(ancestorCount);
            assertThat(branchHash)
                    .as("the hash is what carries the identity signal for swap-vs-delete")
                    .isNotEqualTo(ancestorHash);
        }

        @Test
        void scenarioC_rangeOnlyChange_currentlyChangesHash_butWouldNotUnderIdentityOnlyHash() {
            // ancestor: {p -> 1.0}. Branch: {p -> [1.0,2.0)}. Identity unchanged, range changed.
            // Under V+H_full (current), the hash changes — every transitive of an upgraded
            // library can drift this way, producing noisy conflicts. Under V+H_id (identities
            // only) or V+H_dep_id (dep-identities only), this assertion would flip.
            String ancestor = hashFor(deps(builder().module("p:p", "1.0").build()));
            String branch = hashFor(deps(builder().module("p:p", "[1.0,2.0)").build()));
            assertThat(branch)
                    .as("range-only change perturbs the hash today")
                    .isNotEqualTo(ancestor);
        }

        @Test
        void scenarioD_addVsAdd_differentNewRequesterEachSide_eachBranchHashDiffersFromAncestor() {
            // ancestor: {p}. Branch: {p, q}. Main: {p, r}. Both branches have count 2, both
            // resolve to the same version, but the hashes diverge from each other.
            String ancestor = hashFor(deps(builder().module("p:p", "1.0").build()));
            String branch = hashFor(deps(builder()
                    .module("p:p", "1.0")
                    .module("q:q", "1.0")
                    .build()));
            String main = hashFor(deps(builder()
                    .module("p:p", "1.0")
                    .module("r:r", "1.0")
                    .build()));
            assertThat(branch).isNotEqualTo(ancestor);
            assertThat(main).isNotEqualTo(ancestor);
            assertThat(branch)
                    .as("today the two branches' hashes also diverge from each other — yielding a"
                            + " modify-vs-modify git conflict whose only cost is human re-resolution")
                    .isNotEqualTo(main);
        }
    }

    // ---- helpers ----

    private static String hashFor(Dependents dependents) {
        return LockStates.computeLines(Map.of(TARGET, dependents))
                .findFirst()
                .orElseThrow()
                .dependentsHash();
    }

    private static int numDependentsFor(Dependents dependents) {
        return LockStates.computeLines(Map.of(TARGET, dependents))
                .findFirst()
                .orElseThrow()
                .numDependents();
    }

    private static Dependents deps(NavigableMap<ComponentIdentifier, Set<VersionConstraint>> dependents) {
        return Dependents.of(dependents);
    }

    private static DependentsBuilder builder() {
        return new DependentsBuilder();
    }

    private static final class DependentsBuilder {
        private final NavigableMap<ComponentIdentifier, Set<VersionConstraint>> map =
                new TreeMap<>(GradleComparators.COMPONENT_IDENTIFIER_COMPARATOR);

        DependentsBuilder module(String groupColonName, String requestedVersion) {
            ComponentIdentifier id = mock(ComponentIdentifier.class);
            when(id.getDisplayName()).thenReturn(groupColonName);
            map.put(id, ImmutableSet.of(versionConstraint(requestedVersion)));
            return this;
        }

        DependentsBuilder project(String projectPath, String requestedVersion) {
            ProjectComponentIdentifier id = mock(ProjectComponentIdentifier.class);
            when(id.getDisplayName()).thenReturn("project " + projectPath);
            map.put(id, ImmutableSet.of(versionConstraint(requestedVersion)));
            return this;
        }

        NavigableMap<ComponentIdentifier, Set<VersionConstraint>> build() {
            return map;
        }
    }

    private static VersionConstraint versionConstraint(String requested) {
        VersionConstraint vc = mock(VersionConstraint.class);
        when(vc.toString()).thenReturn(requested);
        return vc;
    }
}
