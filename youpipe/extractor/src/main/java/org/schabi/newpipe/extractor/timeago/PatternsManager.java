package org.schabi.newpipe.extractor.timeago;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonParserException;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class PatternsManager {
    private static final String PATTERNS_RESOURCE = "/unique_patterns.json";

    private static volatile Map<String, PatternsHolder> patternMap;

    private PatternsManager() {
    }

    /**
     * Return an holder object containing all the patterns array.
     *
     * @return an object containing the patterns. If not existent, {@code null}.
     */
    @Nullable
    public static PatternsHolder getPatterns(@Nonnull final String languageCode,
                                             @Nullable final String countryCode) {
        final String targetLocalizationClassName = languageCode
                + (countryCode == null || countryCode.isEmpty() ? "" : "_" + countryCode);
        return getPatternMap().get(targetLocalizationClassName);
    }

    private static Map<String, PatternsHolder> getPatternMap() {
        Map<String, PatternsHolder> map = patternMap;
        if (map == null) {
            synchronized (PatternsManager.class) {
                map = patternMap;
                if (map == null) {
                    map = loadPatterns();
                    patternMap = map;
                }
            }
        }
        return map;
    }

    private static Map<String, PatternsHolder> loadPatterns() {
        final Map<String, PatternsHolder> map = new HashMap<>();
        try (InputStream inputStream =
                     PatternsManager.class.getResourceAsStream(PATTERNS_RESOURCE)) {
            if (inputStream == null) {
                throw new IllegalStateException(
                        "Could not find time ago patterns resource " + PATTERNS_RESOURCE);
            }
            final JsonObject root = JsonParser.object().from(inputStream);
            for (final Map.Entry<String, Object> entry : root.entrySet()) {
                map.put(entry.getKey(), holderFrom((JsonObject) entry.getValue()));
            }
        } catch (final JsonParserException | IOException e) {
            throw new IllegalStateException("Could not load time ago patterns", e);
        }
        return Collections.unmodifiableMap(map);
    }

    private static PatternsHolder holderFrom(@Nonnull final JsonObject value) {
        return new PatternsHolder(
                value.getString("word_separator", ""),
                stringList(value.getArray("seconds")),
                stringList(value.getArray("minutes")),
                stringList(value.getArray("hours")),
                stringList(value.getArray("days")),
                stringList(value.getArray("weeks")),
                stringList(value.getArray("months")),
                stringList(value.getArray("years")));
    }

    private static Collection<String> stringList(@Nullable final JsonArray array) {
        if (array == null) {
            return Collections.emptyList();
        }
        final List<String> list = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            list.add(array.getString(i));
        }
        return list;
    }
}
