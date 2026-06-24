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
        "https://calendar.google.com/calendar/ical/en.%s%%23holiday%%40group.v.calendar.google.com/public/basic.ics";

    // Verified Google holiday-feed slugs (the part after "en."). Probed for
    // HTTP 200; names come from each feed so this is just the id list.
    private static final String[] SLUGS = {
        "usa", "uk", "canadian", "australian", "indian", "irish", "french", "german",
        "italian", "spain", "portuguese", "dutch", "danish", "finnish", "norwegian",
        "swedish", "polish", "russian", "ukrainian", "austrian", "bulgarian", "croatian",
        "czech", "greek", "hungarian", "latvian", "lithuanian", "romanian", "slovak",
        "slovenian", "turkish", "japanese", "china", "taiwan", "hong_kong", "south_korea",
        "singapore", "indonesian", "malaysia", "philippines", "vietnamese", "brazilian",
        "mexican", "new_zealand", "jewish",
    };

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(30))
        .build();

    public static void main(String[] args) throws Exception {
        File outDir = new File("calendar/src/main/assets/holidays");
        if (outDir.exists()) {
            File[] old = outDir.listFiles((d, n) -> n.endsWith(".json"));
            if (old != null) for (File f : old) f.delete();
        } else if (!outDir.mkdirs()) {
            throw new IllegalStateException("Could not create " + outDir.getAbsolutePath());
        }

        List<String[]> index = new ArrayList<>(); // {code, name}
        for (String slug : SLUGS) {
            try {
                String ics = get(String.format(URL_TEMPLATE, slug));
                List<String> lines = unfold(ics);
                String name = calendarName(lines);
                if (name == null) { System.err.println("No name for " + slug); continue; }
                String code = name.replaceAll("[^A-Za-z0-9]", "");

                List<String[]> holidays = parseEvents(lines); // {date, summary}
                if (holidays.isEmpty()) { System.out.println("Skipping " + name + " (no events)"); continue; }
                holidays.sort((a, b) -> {
                    int c = a[0].compareTo(b[0]);
                    return c != 0 ? c : a[1].compareToIgnoreCase(b[1]);
                });

                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < holidays.size(); i++) {
                    if (i > 0) sb.append(',');
                    sb.append("{\"d\":\"").append(holidays.get(i)[0]).append("\",\"n\":")
                        .append(jsonString(holidays.get(i)[1])).append('}');
                }
                sb.append(']');
                Files.write(new File(outDir, code + ".json").toPath(),
                    sb.toString().getBytes(StandardCharsets.UTF_8));
                index.add(new String[]{code, name});
                System.out.println(name + " (" + holidays.size() + " entries)");
            } catch (Exception ex) {
                System.err.println("Skipping " + slug + ": " + ex.getMessage());
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
        Files.write(new File(outDir, "index.json").toPath(),
            idx.toString().getBytes(StandardCharsets.UTF_8));

        System.out.println("Wrote " + index.size() + " countries to " + outDir.getAbsolutePath());
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
