import de.focus_shift.jollyday.core.Holiday;
import de.focus_shift.jollyday.core.HolidayCalendar;
import de.focus_shift.jollyday.core.HolidayManager;
import de.focus_shift.jollyday.core.ManagerParameters;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Year;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Generates the calendar app's bundled holiday data.
 *
 * For every country Jollyday knows, writes assets/holidays/&lt;code&gt;.json
 * (a date-sorted list of {"d":"yyyy-MM-dd","n":"name"}) and an index.json
 * ({"code","name"}). Years [START..END] inclusive.
 */
public final class HolidayGen {
    private static final int START = 2015;
    private static final int END = 2035;

    public static void main(String[] args) throws Exception {
        File outDir = new File("calendar/src/main/assets/holidays");
        if (!outDir.exists() && !outDir.mkdirs()) {
            throw new IllegalStateException("Could not create " + outDir.getAbsolutePath());
        }

        List<String[]> index = new ArrayList<>(); // {code, name}

        for (HolidayCalendar hc : HolidayCalendar.values()) {
            try {
                HolidayManager manager = HolidayManager.getInstance(ManagerParameters.create(hc));
                String code = hc.getId(); // ISO 3166-1 alpha-2, lower-case
                // Skip non-country calendars (stock exchanges like NYSE/LME/TARGET):
                // real country codes are exactly two letters.
                if (code == null || code.length() != 2 || !code.chars().allMatch(Character::isLetter)) {
                    continue;
                }
                String name = manager.getCalendarHierarchy().getDescription(Locale.ENGLISH);

                List<String[]> holidays = new ArrayList<>(); // {date, name}
                for (int year = START; year <= END; year++) {
                    Set<Holiday> set = manager.getHolidays(Year.of(year));
                    for (Holiday h : set) {
                        holidays.add(new String[]{h.getDate().toString(), h.getDescription(Locale.ENGLISH)});
                    }
                }
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
            } catch (Exception e) {
                System.err.println("Skipping " + hc + ": " + e.getMessage());
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
