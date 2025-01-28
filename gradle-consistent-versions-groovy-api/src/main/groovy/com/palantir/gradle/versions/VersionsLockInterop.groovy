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

import org.gradle.api.Project

/**
 * This is groovy so we can use it's automatic reflection abilities to call the correct methods. This avoids having
 * a direct dependency on GCV, which is sometimes using in `plugins {` blocks so is in a different classloader, making
 * configuring using Java correctly without ClassCastExceptions exceedingly difficult.
 * All these methods will only exclude if GCV is applied
 */
public final class VersionsLockInterop {
    enum Scope {
        PRODUCTION,
        TEST
    }

    public static void lockConfiguration(Project project, Scope scope, String configurationName) {
        withVersionsLockExtension(project) { versionsLockExtension ->
            def closure = {
                from configurationName
            }
            switch (scope) {
                case Scope.PRODUCTION:
                    versionsLockExtension.production(closure)
                    break
                case Scope.TEST:
                    versionsLockExtension.test(closure)
                    break
            }
        }
    }

    public static void disableJavaPluginDefaults(Project project) {
        withVersionsLockExtension(project) { versionsLockExtension ->
            versionsLockExtension.disableJavaPluginDefaults()
        }
    }

    private static void withVersionsLockExtension(Project project, Closure closure) {
        project.pluginManager.withPlugin('com.palantir.versions-lock') {
            def versionsLockExtension = project.extensions.getByName('versionsLock')
            closure.call(versionsLockExtension)
        }
    }

    private VersionsLockInterop() {}
}
