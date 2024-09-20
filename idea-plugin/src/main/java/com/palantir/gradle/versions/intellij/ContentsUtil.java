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
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ContentsUtil {
    private static final Logger log = LoggerFactory.getLogger(ContentsUtil.class);

    private ContentsUtil() {
        // Utility class; prevent instantiation
    }

    public static Optional<String> fetchPageContents(URL pageUrl) {
        ProgressIndicator indicator = ProgressManager.getInstance().getProgressIndicator();

        if (indicator == null) {
            return Optional.empty();
        }

        try {
            Future<String> future =
                    ApplicationManager.getApplication().executeOnPooledThread(fetchContentTask(pageUrl, indicator));
            String content =
                    com.intellij.openapi.application.ex.ApplicationUtil.runWithCheckCanceled(future::get, indicator);
            return Optional.ofNullable(content);
        } catch (InterruptedException | ProcessCanceledException e) {
            log.debug("Fetch operation was cancelled", e);
        } catch (Exception e) {
            log.warn("Failed to fetch contents", e);
        }
        return Optional.empty();
    }

    private static Callable<String> fetchContentTask(URL pageUrl, ProgressIndicator indicator) {
        return () -> {
            HttpURLConnection connection = (HttpURLConnection) pageUrl.openConnection();
            connection.setRequestMethod("GET");

            if (connection.getResponseCode() != 200) {
                connection.disconnect();
                throw new ProcessCanceledException();
            }

            BufferedReader in =
                    new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            String inputLine;

            while ((inputLine = in.readLine()) != null) {
                if (indicator.isCanceled()) {
                    throw new InterruptedException("Fetch cancelled");
                }
                result.append(inputLine);
            }

            in.close();
            connection.disconnect();
            return result.toString();
        };
    }
}
