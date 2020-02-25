package com.palantir.gradle.versions;

import com.palantir.gradle.versions.ParameterizedClass.Parameter;
import com.palantir.gradle.versions.ParameterizedClass.Parameters;
import java.io.File;
import java.util.List;
import nebula.test.dependencies.DependencyGraph;
import nebula.test.dependencies.GradleDependencyGenerator;
import org.codehaus.groovy.runtime.DefaultGroovyMethods;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

@ExtendWith(com.palantir.gradle.versions.ParameterizedClass.class)
public class IntegrationSpec extends AbstractIntegrationTestKit {

    @TempDir
    public File gradleTestKitDir;

    @Parameter(0)
    public String gradleVersion;

    @Parameters(name = "Gradle {0}")
    public static Object[][] data() {
        return GradleTestVersions.GRADLE_VERSIONS.stream()
                .map(it -> new Object[] {it})
                .toArray(Object[][]::new);
    }

    @BeforeEach
    public final void beforeEachMain(TestInfo testInfo) {
        String testName = (testInfo.getDisplayName() + " "
                        + testInfo.getTestMethod().get().getName())
                .replace("()", "");
        super.initialize(testInfo.getTestClass().get(), testName);

        super.setGradleVersion(gradleVersion);

        setKeepFiles(true);
        // testKit.debug = true // TODO cannot do this when running in parallel
        // Ends up in: org.gradle.testkit.runner.internal.ToolingApiGradleExecutor.buildConnector -> embedded
    }

    @AfterEach
    public final void afterEachMain() {
        cleanup();
    }

    @Override
    public GradleRunner createRunner(String... tasks) {
        // Very important: need each test kit to use a different location
        // Currently it sets it to a static location via
        // org.gradle.testkit.runner.internal.DefaultGradleRunner.calculateTestKitDirProvider
        return super.createRunner(tasks).withTestKitDir(gradleTestKitDir);
    }

    @Override
    public List<String> calculateArguments(String... args) {
        List<String> list = super.calculateArguments(args);
        DefaultGroovyMethods.push(list, "--warning-mode=all");
        return list;
    }

    protected File generateMavenRepo(String... graph) {
        DependencyGraph dependencyGraph = new DependencyGraph(graph);
        GradleDependencyGenerator generator = new GradleDependencyGenerator(
                dependencyGraph, new File(getProjectDir(), "build/testrepogen").toString());
        // TODO this does not work in parallel.
        // return generator.generateTestMavenRepo()
        GradleRunner.create()
                .withProjectDir(generator.getGradleRoot())
                .withTestKitDir(gradleTestKitDir)
                .withArguments("publishMavenPublicationToMavenRepository")
                .build();
        return generator.getMavenRepoDir();
    }
}
