import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates the calendar app's bundled holiday data from Google's public
 * iCal holiday feeds (en.&lt;slug&gt;#holiday@group.v.calendar.google.com).
 *
 * Downloads each feed (host-side), derives the country name from X-WR-CALNAME,
 * parses VEVENTs into date-sorted {"d","n"} entries, and writes
 * assets/holidays/&lt;code&gt;.json plus index.json. Feeds that don't resolve
 * (non-200) or are empty are skipped. No app-side dependency, no runtime network.
 */
public final class HolidayGen {
    private static final String URL_TEMPLATE =
        "https://calendar.google.com/calendar/ical/%s.%s%%23holiday%%40group.v.calendar.google.com/public/basic.ics";

    // 67 Google-supported language prefixes with display names
    private static final String[][] LANGS = {
        {"en", "English"}, {"es", "Spanish"}, {"fr", "French"}, {"de", "German"},
        {"it", "Italian"}, {"pt", "Portuguese"}, {"ru", "Russian"}, {"zh", "Chinese"},
        {"ja", "Japanese"}, {"ko", "Korean"}, {"ar", "Arabic"}, {"hi", "Hindi"},
        {"nl", "Dutch"}, {"pl", "Polish"}, {"tr", "Turkish"}, {"sv", "Swedish"},
        {"da", "Danish"}, {"no", "Norwegian"}, {"fi", "Finnish"}, {"cs", "Czech"},
        {"hu", "Hungarian"}, {"el", "Greek"}, {"he", "Hebrew"}, {"th", "Thai"},
        {"vi", "Vietnamese"}, {"id", "Indonesian"}, {"ms", "Malay"}, {"uk", "Ukrainian"},
        {"ro", "Romanian"}, {"bg", "Bulgarian"}, {"hr", "Croatian"}, {"sk", "Slovak"},
        {"sl", "Slovenian"}, {"lt", "Lithuanian"}, {"lv", "Latvian"}, {"et", "Estonian"},
        {"sr", "Serbian"}, {"ca", "Catalan"}, {"eu", "Basque"}, {"gl", "Galician"},
        {"is", "Icelandic"}, {"ga", "Irish"}, {"mt", "Maltese"}, {"cy", "Welsh"},
        {"af", "Afrikaans"}, {"sq", "Albanian"}, {"hy", "Armenian"}, {"az", "Azerbaijani"},
        {"be", "Belarusian"}, {"bn", "Bengali"}, {"bs", "Bosnian"}, {"ka", "Georgian"},
        {"km", "Khmer"}, {"kn", "Kannada"}, {"ky", "Kyrgyz"}, {"lo", "Lao"},
        {"mk", "Macedonian"}, {"ml", "Malayalam"}, {"mn", "Mongolian"}, {"my", "Burmese"},
        {"ne", "Nepali"}, {"pa", "Punjabi"}, {"si", "Sinhala"}, {"ta", "Tamil"},
        {"te", "Telugu"}, {"ur", "Urdu"}, {"uz", "Uzbek"},
    };

    // Verified Google holiday-feed slugs (the part after lang prefix). Probed for
    // HTTP 200; names come from each feed so this is just the id list.
    // 202 total: 197 countries/territories + 5 religious calendars
    private static final String[] SLUGS = {
        "usa", "uk", "canadian", "australian", "indian", "irish", "french", "german",
        "italian", "spain", "portuguese", "dutch", "danish", "finnish", "norwegian", "swedish",
        "polish", "russian", "ukrainian", "austrian", "bulgarian", "croatian", "czech", "greek",
        "hungarian", "latvian", "lithuanian", "romanian", "slovak", "slovenian", "turkish", "japanese",
        "china", "taiwan", "hong_kong", "south_korea", "singapore", "indonesian", "malaysia", "philippines",
        "vietnamese", "brazilian", "mexican", "new_zealand", "jewish", "christian", "islamic", "judaism",
        "hinduism", "orthodox_christianity", "sa", "ar", "cl", "co", "pe", "th",
        "be", "ch", "lu", "is", "rs", "eg", "pk", "bd",
        "af", "al", "dz", "ad", "ao", "ag", "am", "az",
        "bs", "bh", "bb", "by", "bz", "bj", "bt", "bo",
        "ba", "bw", "bn", "bf", "bi", "cv", "kh", "cm",
        "cf", "td", "km", "cg", "cd", "cr", "ci", "cu",
        "cy", "dj", "dm", "do", "ec", "sv", "gq", "er",
        "ee", "sz", "et", "fj", "ga", "gm", "ge", "gh",
        "gd", "gt", "gn", "gw", "gy", "ht", "hn", "ir",
        "iq", "jm", "jo", "kz", "ke", "ki", "kw", "kg",
        "la", "lb", "ls", "lr", "ly", "li", "mg", "mw",
        "mv", "ml", "mt", "mh", "mr", "mu", "fm", "md",
        "mc", "mn", "me", "ma", "mz", "mm", "na", "nr",
        "np", "ni", "ne", "ng", "mk", "om", "pw", "pa",
        "pg", "py", "qa", "rw", "kn", "lc", "vc", "ws",
        "sm", "st", "sn", "sc", "sl", "sb", "so", "ss",
        "lk", "sd", "sr", "sy", "tj", "tz", "tl", "tg",
        "to", "tt", "tn", "tm", "tv", "ug", "ae", "uy",
        "uz", "vu", "ve", "ye", "zm", "zw", "mo", "pr",
        "gu", "vi",
    };

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(30))
        .build();

    public static void main(String[] args) throws Exception {
        String startSlug = args.length > 0 ? args[0] : null;
        File baseDir = new File("calendar/src/main/assets/holidays");
        boolean resume = startSlug != null && baseDir.exists();
        if (!resume) {
            deleteRecursively(baseDir);
        }
        if (!baseDir.exists() && !baseDir.mkdirs()) {
            throw new IllegalStateException("Could not create " + baseDir.getAbsolutePath());
        }
        if (resume) {
            System.out.println("Resuming from slug: " + startSlug);
        }

        // Write languages.json at top level
        StringBuilder langsJson = new StringBuilder("[");
        for (int i = 0; i < LANGS.length; i++) {
            if (i > 0) langsJson.append(',');
            langsJson.append("{\"code\":\"").append(LANGS[i][0]).append("\",\"name\":")
                .append(jsonString(LANGS[i][1])).append('}');
        }
        langsJson.append(']');
        Files.write(new File(baseDir, "languages.json").toPath(),
            langsJson.toString().getBytes(StandardCharsets.UTF_8));

        // Phase 1: Build canonical slug -> code mapping from English feed
        // This ensures all languages use same code filenames (e.g. Malaysia.json, not JoursfrisenMalaisie.json)
        System.out.println("Phase 1/2: Building canonical index from English...");
        java.util.Map<String, String> slugToCode = new java.util.HashMap<>();
        java.util.Map<String, String> codeToName = new java.util.HashMap<>();
        List<String[]> canonicalIndex = new ArrayList<>();
        int totalSlugs = SLUGS.length;
        for (int i = 0; i < totalSlugs; i++) {
            String slug = SLUGS[i];
            printProgress("  Fetching English", i + 1, totalSlugs);
            try {
                String ics = get(String.format(URL_TEMPLATE, "en", slug));
                List<String> lines = unfold(ics);
                String name = calendarName(lines);
                if (name == null) continue;
                String code = name.replaceAll("[^A-Za-z0-9]", "");
                slugToCode.put(slug, code);
                codeToName.put(code, name);
                canonicalIndex.add(new String[]{code, name});
            } catch (Exception ex) {
                System.err.println("\n  [en] Skipping " + slug + ": " + ex.getMessage());
            }
        }
        System.out.println(); // newline after progress bar
        canonicalIndex.sort((a, b) -> a[1].compareToIgnoreCase(b[1]));
        System.out.println("  ✓ Canonical: " + canonicalIndex.size() + " countries found\n");

        // Write top-level index.json
        StringBuilder topIdx = new StringBuilder("[");
        for (int i = 0; i < canonicalIndex.size(); i++) {
            if (i > 0) topIdx.append(',');
            topIdx.append("{\"code\":\"").append(canonicalIndex.get(i)[0]).append("\",\"name\":")
                .append(jsonString(canonicalIndex.get(i)[1])).append('}');
        }
        topIdx.append(']');
        Files.write(new File(baseDir, "index.json").toPath(), topIdx.toString().getBytes(StandardCharsets.UTF_8));

        // Phase 2: For each slug, fetch English first, then other langs, only write non-English if different
        System.out.println("Phase 2/2: Fetching localized holidays (comparing to English, only saving differences)...");
        java.util.Map<String, java.util.Set<String>> countryLangs = new java.util.HashMap<>(); // code -> set of lang codes with distinct content
        java.util.Map<String, String> langCodeToName = new java.util.HashMap<>();
        for (String[] lp : LANGS) langCodeToName.put(lp[0], lp[1]);

        // Load existing country_languages.json if resuming
        if (resume) {
            File clFile = new File(baseDir, "country_languages.json");
            if (clFile.exists()) {
                try {
                    String clText = new String(Files.readAllBytes(clFile.toPath()), StandardCharsets.UTF_8);
                    // Simple parse: {"Code":["en","fr"],...}
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"([^\"]+)\":\\[([^\\]]*)\\]").matcher(clText);
                    while (m.find()) {
                        String c = m.group(1);
                        String[] langs = m.group(2).replace("\"", "").split(",");
                        java.util.Set<String> set = new java.util.HashSet<>();
                        for (String l : langs) if (!l.trim().isEmpty()) set.add(l.trim());
                        if (!set.isEmpty()) countryLangs.put(c, set);
                    }
                    System.out.println("  Loaded existing progress for " + countryLangs.size() + " countries");
                } catch (Exception e) { /* ignore */ }
            }
        }

        int totalWritten = 0;
        int totalRequests = 0;
        int totalCountries = slugToCode.size();
        int countryIdx = 0;
        int totalLangs = LANGS.length - 1; // excluding English
        boolean started = (startSlug == null);

        for (String slug : SLUGS) {
            if (!started) {
                if (slug.equals(startSlug)) started = true;
                else continue;
            }
            String code = slugToCode.get(slug);
            if (code == null) continue;
            String displayName = codeToName.get(code);
            countryIdx++;

            // Fetch English baseline
            List<String[]> enHolidays;
            try {
                String enIcs = get(String.format(URL_TEMPLATE, "en", slug));
                enHolidays = parseEvents(unfold(enIcs));
                if (enHolidays.isEmpty()) continue;
                enHolidays.sort((a, b) -> { int c = a[0].compareTo(b[0]); return c != 0 ? c : a[1].compareToIgnoreCase(b[1]); });
            } catch (Exception ex) {
                System.err.println("\n  [en] Skipping " + slug + ": " + ex.getMessage());
                continue;
            }

            // Write English
            File enDir = new File(baseDir, "en");
            enDir.mkdirs();
            writeHolidays(new File(enDir, code + ".json"), enHolidays);
            countryLangs.computeIfAbsent(code, k -> new java.util.HashSet<>()).add("en");
            totalWritten++;

            // Fetch other languages, compare to English, only write if different
            int langIdx = 0;
            int distinctCount = 1; // English
            for (String[] langPair : LANGS) {
                String lang = langPair[0];
                if (lang.equals("en")) continue;
                langIdx++;
                totalRequests++;
                printProgress("  [" + countryIdx + "/" + totalCountries + "] " + code + " (" + distinctCount + " langs)", langIdx, totalLangs);
                try {
                    String ics = get(String.format(URL_TEMPLATE, lang, slug));
                    List<String[]> holidays = parseEvents(unfold(ics));
                    if (holidays.isEmpty()) continue;
                    holidays.sort((a, b) -> { int c = a[0].compareTo(b[0]); return c != 0 ? c : a[1].compareToIgnoreCase(b[1]); });

                    if (holidaysEqual(enHolidays, holidays)) {
                        // Same as English, skip to save space
                        continue;
                    }

                    File langDir = new File(baseDir, lang);
                    langDir.mkdirs();
                    writeHolidays(new File(langDir, code + ".json"), holidays);
                    countryLangs.computeIfAbsent(code, k -> new java.util.HashSet<>()).add(lang);
                    distinctCount++;
                    totalWritten++;
                } catch (Exception ex) {
                    // skip silently — many lang×country combos don't exist or 500
                }
            }
        }
        System.out.println(); // newline after progress bar

        // Write per-language index.json listing only countries with distinct content in that lang
        for (String[] langPair : LANGS) {
            String lang = langPair[0];
            File langDir = new File(baseDir, lang);
            if (!langDir.exists()) continue;
            List<String[]> index = new ArrayList<>();
            for (String[] entry : canonicalIndex) {
                String code = entry[0];
                String name = entry[1];
                if (new File(langDir, code + ".json").exists()) {
                    index.add(new String[]{code, name});
                }
            }
            index.sort((a, b) -> a[1].compareToIgnoreCase(b[1]));
            StringBuilder idx = new StringBuilder("[");
            for (int i = 0; i < index.size(); i++) {
                if (i > 0) idx.append(',');
                idx.append("{\"code\":\"").append(index.get(i)[0]).append("\",\"name\":")
                    .append(jsonString(index.get(i)[1])).append('}');
            }
            idx.append(']');
            Files.write(new File(langDir, "index.json").toPath(), idx.toString().getBytes(StandardCharsets.UTF_8));
        }

        // Copy English to flat structure for backward compat
        File enDir = new File(baseDir, "en");
        File[] enFiles = enDir.listFiles((d, n) -> n.endsWith(".json") && !n.equals("index.json"));
        if (enFiles != null) {
            for (File src : enFiles) {
                Files.copy(src.toPath(), new File(baseDir, src.getName()).toPath());
            }
        }

        // Write country_languages.json mapping code -> list of available lang codes
        StringBuilder cl = new StringBuilder("{");
        boolean first = true;
        for (java.util.Map.Entry<String, java.util.Set<String>> e : countryLangs.entrySet()) {
            if (!first) cl.append(',');
            first = false;
            cl.append('"').append(e.getKey()).append("\":[");
            boolean firstLang = true;
            for (String lc : e.getValue()) {
                if (!firstLang) cl.append(',');
                firstLang = false;
                cl.append('"').append(lc).append('"');
            }
            cl.append(']');
        }
        cl.append('}');
        Files.write(new File(baseDir, "country_languages.json").toPath(), cl.toString().getBytes(StandardCharsets.UTF_8));

        System.out.println("  ✓ Wrote " + totalWritten + " files across " + LANGS.length + " languages to " + baseDir.getAbsolutePath());
    }

    private static void printProgress(String label, int current, int total) {
        int width = 30;
        int filled = (int) ((double) current / total * width);
        StringBuilder bar = new StringBuilder("\r" + label + " [");
        for (int i = 0; i < width; i++) bar.append(i < filled ? "█" : "░");
        bar.append("] ").append(current).append("/").append(total)
           .append(" (").append(String.format("%.0f", (double) current / total * 100)).append("%)");
        System.out.print(bar.toString());
        if (current == total) System.out.println();
        System.out.flush();
    }

    private static boolean holidaysEqual(List<String[]> a, List<String[]> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i)[0].equals(b.get(i)[0]) || !a.get(i)[1].equals(b.get(i)[1])) return false;
        }
        return true;
    }

    private static void writeHolidays(File out, List<String[]> holidays) throws Exception {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < holidays.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"d\":\"").append(holidays.get(i)[0]).append("\",\"n\":")
                .append(jsonString(holidays.get(i)[1])).append('}');
        }
        sb.append(']');
        Files.write(out.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void deleteRecursively(File dir) throws Exception {
        if (!dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) deleteRecursively(f);
                else f.delete();
            }
        }
        dir.delete();
    }

    private static String calendarName(List<String> lines) {
        for (String line : lines) {
            if (line.startsWith("X-WR-CALNAME:")) {
                return line.substring("X-WR-CALNAME:".length())
                    .replaceFirst("^Holidays and Observances in ", "")
                    .replaceFirst("^Holidays in ", "")
                    .trim();
            }
        }
        return null;
    }

    private static List<String[]> parseEvents(List<String> lines) {
        List<String[]> out = new ArrayList<>();
        boolean inEvent = false;
        String summary = null, date = null;
        for (String line : lines) {
            if (line.equals("BEGIN:VEVENT")) { inEvent = true; summary = null; date = null; continue; }
            if (line.equals("END:VEVENT")) {
                if (summary != null && date != null) out.add(new String[]{date, summary});
                inEvent = false; continue;
            }
            if (!inEvent) continue;
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String left = line.substring(0, colon).toUpperCase();
            String value = line.substring(colon + 1);
            if (left.equals("SUMMARY")) {
                summary = unescape(value);
            } else if (left.startsWith("DTSTART")) {
                Matcher d = Pattern.compile("(\\d{4})(\\d{2})(\\d{2})").matcher(value);
                if (d.find()) date = d.group(1) + "-" + d.group(2) + "-" + d.group(3);
            }
        }
        return out;
    }

    private static List<String> unfold(String ics) {
        List<String> out = new ArrayList<>();
        for (String raw : ics.split("\\r?\\n")) {
            if ((raw.startsWith(" ") || raw.startsWith("\t")) && !out.isEmpty()) {
                out.set(out.size() - 1, out.get(out.size() - 1) + raw.substring(1));
            } else {
                out.add(raw);
            }
        }
        return out;
    }

    private static String unescape(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case 'n': case 'N': b.append(' '); break;
                    case ',': b.append(','); break;
                    case ';': b.append(';'); break;
                    case '\\': b.append('\\'); break;
                    default: b.append(n);
                }
            } else {
                b.append(c);
            }
        }
        return b.toString().trim();
    }

    private static String get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .header("User-Agent", "Mozilla/5.0 (holidaygen)")
            .timeout(Duration.ofSeconds(60))
            .GET().build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("HTTP " + resp.statusCode());
        }
        return resp.body();
    }

    private static String jsonString(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append('"').toString();
    }
}
