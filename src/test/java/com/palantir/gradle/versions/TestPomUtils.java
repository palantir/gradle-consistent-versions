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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class TestPomUtils {
    private TestPomUtils() {}

    public static void makePlatformPom(File repo, String group, String name, String version) throws IOException {
        Path dir = repo.toPath().resolve(group).resolve(name).resolve(version);
        Files.createDirectories(dir);
        String pomContent = String.format("""
            <?xml version="1.0" encoding="UTF-8"?>
            <project xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd" xmlns="http://maven.apache.org/POM/4.0.0"
                xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
              <modelVersion>4.0.0</modelVersion>
              <packaging>pom</packaging>
              <groupId>%s</groupId>
              <artifactId>%s</artifactId>
              <version>%s</version>
              <dependencyManagement>
                <dependencies>
                </dependencies>
              </dependencyManagement>
            </project>
            """, group, name, version);
        Files.writeString(dir.resolve("platform-1.0.pom"), pomContent);
    }

    public static String pomWithJarPackaging(String group, String artifact, String version) {
        return String.format("""
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
            <modelVersion>4.0.0</modelVersion>
            <groupId>%s</groupId>
            <artifactId>%s</artifactId>
            <packaging>jar</packaging>
            <version>%s</version>
            <description/>
            <dependencies/>
            </project>
            """, group, artifact, version);
    }
}
