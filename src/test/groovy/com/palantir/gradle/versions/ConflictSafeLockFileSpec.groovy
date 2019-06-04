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

package com.palantir.gradle.versions

import com.palantir.gradle.versions.lockstate.LockState
import java.nio.file.Paths
import spock.lang.Specification

class ConflictSafeLockFileSpec extends Specification {

    def 'should parse a lock file successfully'() {
        def file = new ConflictSafeLockFile(Paths.get("src/test/resources/sample-versions.lock"))

        when:
        LockState locks = file.readLocks()

        then:
        locks.productionLinesByModuleIdentifier().size() == 27
        locks.testLinesByModuleIdentifier().size() == 0
    }
}
