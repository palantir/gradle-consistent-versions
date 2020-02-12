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

import groovy.transform.CompileStatic
import nebula.test.BaseIntegrationSpec
import nebula.test.IntegrationTestKitSpec
import nebula.test.dependencies.DependencyGraph
import nebula.test.dependencies.GradleDependencyGenerator
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInfo
import org.junit.jupiter.api.io.TempDir
import spock.lang.Specification

@CompileStatic
class IntegrationSpec {
    public static final String testKitDirProperty = "org.gradle.testkit.dir"
    @Delegate(excludeTypes = [ Specification ], interfaces = true, allNames = true)
    private ExposedIntegrationTestKitSpec testKit = new ExposedIntegrationTestKitSpec()

    @TempDir
    public File gradleTestKitDir

    @BeforeEach
    final void beforeEachMain(TestInfo testInfo) {
        testKit.testInfo = testInfo

        // Need to unwind spock's order of 'setup' methods
        // First call the one on BaseIntegrationSpec, then the one on IntegrationTestKitSpec
        testKit.baseSetup()
        invokeHiddenMethod(testKit, IntegrationTestKitSpec, 'setup')

        testKit.keepFiles = true
        // testKit.debug = true // TODO cannot do this when running in parallel
        // Ends up in: org.gradle.testkit.runner.internal.ToolingApiGradleExecutor.buildConnector -> embedded
        testKit.settingsFile.createNewFile()
    }

    @AfterEach
    final void afterEachMain() {
        invokeHiddenMethod(testKit, IntegrationTestKitSpec, 'cleanup')
        System.clearProperty(testKitDirProperty)
    }

    private static <T> void invokeHiddenMethod(T obj, Class<T> clazz, String methodName) {
        def baseSetup = clazz.declaredMethods.find { it.name == methodName }
        baseSetup.setAccessible(true)
        baseSetup.invoke(obj)
    }

    @CompileStatic
    protected File generateMavenRepo(String... graph) {
        DependencyGraph dependencyGraph = new DependencyGraph(graph)
        GradleDependencyGenerator generator = new GradleDependencyGenerator(
                dependencyGraph, new File(testKit.projectDir, "build/testrepogen").toString())
        return generator.generateTestMavenRepo()
    }

    class ExposedIntegrationTestKitSpec extends IntegrationTestKitSpec {
        private TestInfo testInfo

        @Override
        File createFile(String path, File baseDir = getProjectDir()) {
            super.createFile(path, baseDir)
        }

        @Override
        File file(String path, File baseDir = getProjectDir()) {
            return super.file(path, baseDir)
        }

        @Override
        File directory(String path, File baseDir = getProjectDir()) {
            return super.directory(path, baseDir)
        }

        @Override
        GradleRunner createRunner(String... tasks) {
            // Very important: need each test kit to use a different location
            // Currently it sets it to a static location via org.gradle.testkit.runner.internal.DefaultGradleRunner.calculateTestKitDirProvider
            return super.createRunner(tasks).withTestKitDir(gradleTestKitDir)
        }
/** Supersedes {@link BaseIntegrationSpec#setup} to use testInfo directly. */
        def baseSetup() {
            def sanitizedTestName = testInfo.displayName
                    .replace('()', "")
                    .replaceAll(/\W+/, '-')
            projectDir = new File("build/nebulatest/${testInfo.testClass.get().canonicalName}/${sanitizedTestName}").absoluteFile
            if (projectDir.exists()) {
                projectDir.deleteDir()
            }
            projectDir.mkdirs()
            moduleName = findModuleName()
        }
    }
}
