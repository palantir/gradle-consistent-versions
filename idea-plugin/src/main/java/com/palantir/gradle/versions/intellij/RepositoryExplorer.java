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

package com.palantir.gradle.versions.intellij;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.NodeList;

public class RepositoryExplorer {

    private final String baseUrl;
    private static final Logger log = LoggerFactory.getLogger(RepositoryExplorer.class);

    public RepositoryExplorer(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public final List<String> getFolders(DependencyGroup group) {
        ProgressIndicator indicator = ProgressManager.getInstance().getProgressIndicator();
        return fetchFoldersFromUrl(baseUrl + group.asUrlString(), indicator);
    }

    public final List<String> getVersions(DependencyGroup group, DependencyPackage dependencyPackage) {
        String metadataUrl = baseUrl + group.asUrlString() + dependencyPackage.packageName() + "/maven-metadata.xml";
        ProgressIndicator indicator = ProgressManager.getInstance().getProgressIndicator();

        if (indicator == null) {
            return new ArrayList<>();
        }

        String metadataContent = fetchUrlContents(metadataUrl, indicator);

        return parseVersionsFromMetadata(metadataContent);
    }

    private String fetchUrlContents(String repoUrl, ProgressIndicator indicator) {
        String content = "";
        try {
            Callable<String> task = () -> {
                URL url = new URL(repoUrl);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");

                if (connection.getResponseCode() != 200) {
                    connection.disconnect();
                    throw new ProcessCanceledException();
                }

                BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                StringBuilder result = new StringBuilder();
                String inputLine;

                while ((inputLine = in.readLine()) != null) {
                    if (indicator != null && indicator.isCanceled()) {
                        throw new InterruptedException("Fetch cancelled");
                    }
                    result.append(inputLine);
                }

                in.close();
                connection.disconnect();
                return result.toString();
            };
            Future<String> future = ApplicationManager.getApplication().executeOnPooledThread(task);
            content = com.intellij.openapi.application.ex.ApplicationUtil.runWithCheckCanceled(future::get, indicator);
        } catch (InterruptedException | ProcessCanceledException e) {
            log.debug("Fetch operation was cancelled", e);
        } catch (Exception e) {
            log.warn("Failed to fetch contents for repo: {}", repoUrl, e);
        }
        return content;
    }

    private List<String> fetchFoldersFromUrl(String repoUrl, ProgressIndicator indicator) {
        List<String> folders = new ArrayList<>();

        String content = fetchUrlContents(repoUrl, indicator);
        if (content == null) {
            return folders;
        }

        Document doc = Jsoup.parse(content);
        Elements links = doc.select("a[href]");

        for (Element link : links) {
            String href = link.attr("href");
            if (href.endsWith("/") && !href.contains(".")) {
                folders.add(href.substring(0, href.length() - 1));
            }
        }
        return folders;
    }

    private List<String> parseVersionsFromMetadata(String metadataContent) {
        List<String> versions = new ArrayList<>();

        if (metadataContent == null || metadataContent.isEmpty()) {
            log.debug("Empty metadata content received");
            return versions;
        }

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            org.w3c.dom.Document doc = builder.parse(new java.io.ByteArrayInputStream(metadataContent.getBytes()));

            NodeList versionNodes = doc.getElementsByTagName("version");
            for (int i = 0; i < versionNodes.getLength(); i++) {
                versions.add(versionNodes.item(i).getTextContent());
            }
        } catch (Exception e) {
            log.debug("Failed to parse maven-metadata.xml", e);
        }
        return versions;
    }
}
