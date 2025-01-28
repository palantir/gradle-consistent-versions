/*
 * (c) Copyright 2025 Palantir Technologies Inc. All rights reserved.
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


import nebula.test.ProjectSpec

class VersionsLockInteropSpec extends ProjectSpec {
    def setup() {
        project.apply plugin: 'com.palantir.consistent-versions'
    }

    def 'can lock a production configuration'() {
        when:
        VersionsLockInterop.lockConfiguration(project, VersionsLockInterop.Scope.PRODUCTION, 'blah')

        then:
        def extension = project.extensions.getByType(VersionsLockExtension)
        extension.productionConfigurations.contains('blah')
    }

    def 'can lock a test configuration'() {
        when:
        VersionsLockInterop.lockConfiguration(project, VersionsLockInterop.Scope.TEST, 'blah')

        then:
        def extension = project.extensions.getByType(VersionsLockExtension)
        extension.testConfigurations.contains('blah')
    }

    def 'can disable java plugins defaults'() {
        when:
        VersionsLockInterop.disableJavaPluginDefaults(project)

        then:
        def extension = project.extensions.getByType(VersionsLockExtension)
        !extension.isUseJavaPluginDefaults()
    }
}
