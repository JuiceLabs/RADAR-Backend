/*
 * Copyright 2017 King's College London and The Hyve
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.radarcns.stream.phone;

import java.io.IOException;
import java.text.ParseException;
import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nullable;
import org.apache.kafka.common.cache.LRUCache;
import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PlayStoreLookup {
    /** Android app category. */
    public static class AppCategory {
        private final String categoryName;
        private final double fetchTimeStamp;

        private AppCategory(String categoryName) {
            this.categoryName = categoryName;
            this.fetchTimeStamp = System.currentTimeMillis() / 1000d;
        }

        /**
         * Get the app category name.
         *
         * @return category or null if the category is unknown.
         */
        public String getCategoryName() {
            return categoryName;
        }

        /**
         * Get the time that the app category was fetched from the Google Play Store.
         * @return time from unix epoch UTC, in seconds
         */
        public double getFetchTimeStamp() {
            return fetchTimeStamp;
        }
    }

    private static final Logger log = LoggerFactory.getLogger(PlayStoreLookup.class);
    private static final String URL_PLAY_STORE_APP_DETAILS = "https://play.google.com/store/apps/details?id=";
    private static final String CATEGORY_ANCHOR_SELECTOR = "a.document-subtitle.category";

    private final long cacheTimeoutSeconds;
    // Do not cache more than 1 million elements, for memory consumption reasons
    private final LRUCache<String, AppCategory> categoryCache;

    public PlayStoreLookup(long cacheTimeoutSeconds, int maxCacheSize) {
        this.cacheTimeoutSeconds = cacheTimeoutSeconds;
        this.categoryCache = new LRUCache<>(maxCacheSize);
    }

    public AppCategory lookupCategory(String packageName) {
        // If not yet in cache, fetch category for this package
        AppCategory category = categoryCache.get(packageName);

        long cacheThreshold = System.currentTimeMillis() / 1000L - cacheTimeoutSeconds;
        if (category == null || category.fetchTimeStamp < cacheThreshold) {
            try {
                category = fetchCategory(packageName);
                categoryCache.put(packageName, category);
            } catch (IOException ex) {
                // do not cache anything: we might have better luck next time
                log.warn("Could not find category of {}: {}", packageName, ex.toString());
                category = new AppCategory(null);
            }
        }

        return category;
    }

    /**
     * Fetches the app category by parsing the Play Store
     * Returning empty string can mean:
     * - Page can't be retrieved because app is not listed in play store
     * - Category element on play store is not available
     * - URL can't be parsed
     * @param packageName name of the package as registered in the play store
     * @return category as given by the play store
     * @throws IOException if the page could not be retrieved (not public) or the Google App store
     *                     is down
     */
    public static AppCategory fetchCategory(String packageName) throws IOException {
        String url = URL_PLAY_STORE_APP_DETAILS + packageName;

        try {
            Document doc = Jsoup.connect(url).get();

            return getCategoryFromDocument(doc, packageName);
        } catch (HttpStatusException ex) {
            log.warn("Package {} page could not be found", packageName);
            return new AppCategory(null);
        }
    }

    static AppCategory getCategoryFromDocument(Document doc, String packageName) {
        Element categoryElement = doc.select(CATEGORY_ANCHOR_SELECTOR).first();

        if (categoryElement != null) {
            String href = categoryElement.attr("href");
            if (href != null) {
                String[] urlSplit = href.split("/");
                return new AppCategory(urlSplit[urlSplit.length - 1]);
            }
        }
        log.warn("Could not find category of {}: "
                + "element containing category could not be found", packageName);
        return new AppCategory(null);
    }
}

