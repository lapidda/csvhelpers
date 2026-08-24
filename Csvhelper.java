// If your project uses packages, add e.g. `package de.optimalhelper.csv;` above
// and move this file to the matching folder under src/main/java.

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reads a transposed CSV from the classpath and writes one JSON file per data column.
 *
 * <p>Expected layout - column 1 holds the attribute names, every further column is
 * one record:
 *
 * <pre>
 * Name;Server 01;Server 02
 * Hostname (FQDN);srv01.example.org;srv02.example.org
 * IP-Adresse;10.0.0.1;n.v.
 * Rolle;Web;DB
 * </pre>
 *
 * produces {@code Server_01.json} and {@code Server_02.json}.
 *
 * <p>Normalization applied:
 * <ul>
 *   <li>Records broken across physical lines (Confluence export damage) are stitched
 *       back together; newlines inside quoted fields are preserved as real newlines.</li>
 *   <li>Attribute names lose any {@code (...)} groups, including nested ones, and are
 *       whitespace-collapsed and trimmed.</li>
 *   <li>Values that are blank, contain {@code n.v.}, or are exactly {@code leer} are
 *       omitted from the JSON entirely.</li>
 *   <li>Output file names have whitespace replaced with {@code _}.</li>
 * </ul>
 *
 * <p>No external dependencies - the JSON is written by hand (the values are always
 * strings, so this stays simple and correct).
 */
public final class CsvToJsonGenerator {

    /** CSV on the classpath, i.e. src/main/resources/FileRaw.csv */
    private static final String INPUT_RESOURCE = "FileRaw.csv";

    /** Output folder, relative to the resources root. */
    private static final String OUTPUT_FOLDER = "testfiles";

    /** Candidate delimiters, sniffed from the header line. */
    private static final char[] DELIMITER_CANDIDATES = {';', ',', '\t', '|'};

    /** Text inserted where a stray line break is removed. */
    private static final String JOIN_WITH = "";

    // ---- nesting configuration ---------------------------------------------
    // The attribute that opens the group. Compare against the CLEANED name, i.e.
    // without any "(...)" part. It stays a normal top-level field itself.
    private static final String GROUP_TRIGGER_ATTRIBUTE = "Conditional";

    /**
     * Value the trigger attribute must have for grouping to happen.
     * {@code null} = always group, regardless of the value.
     * Set to {@code "N"} to only nest when the trigger reads N.
     */
    private static final String GROUP_TRIGGER_VALUE = null;

    /**
     * Grouping stops when this attribute is reached; that attribute and everything
     * after it are written at top level again. {@code null} = group to end of record.
     */
    private static final String GROUP_END_ATTRIBUTE = "GroupEnd";

    /** Name of the nested JSON object. */
    private static final String GROUP_NAME = "customGroup";

    private static final Pattern INNERMOST_BRACKETS = Pattern.compile("\\([^()]*\\)");
    private static final Pattern UNCLOSED_BRACKET = Pattern.compile("\\([^)]*$");
    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");
    private static final Pattern NOT_AVAILABLE = Pattern.compile("n\\.\\s*v\\.", Pattern.CASE_INSENSITIVE);
    private static final Pattern LEER = Pattern.compile("^leer$", Pattern.CASE_INSENSITIVE);
    private static final Pattern INVALID_FILENAME_CHARS = Pattern.compile("[\\\\/:*?\"<>|]");

    private CsvToJsonGenerator() {
    }

    public static void main(String[] args) throws IOException {
        Path outputDir = resolveOutputDirectory();
        List<Path> written = generate(outputDir);

        System.out.println("Wrote " + written.size() + " file(s) to " + outputDir.toAbsolutePath());
        for (Path p : written) {
            System.out.println("  " + p.getFileName());
        }
    }

    // ------------------------------------------------------------------
    // main pipeline
    // ------------------------------------------------------------------

    /** Reads the CSV resource and writes one JSON file per record column. */
    public static List<Path> generate(Path outputDir) throws IOException {
        String csv = readResource(INPUT_RESOURCE);

        List<String> physicalLines = splitLines(csv);
        if (physicalLines.size() < 2) {
            throw new IOException(INPUT_RESOURCE + " has fewer than two non-empty lines.");
        }

        char delimiter = detectDelimiter(physicalLines.get(0));
        List<String> records = stitchBrokenRecords(physicalLines, delimiter);

        List<List<String>> table = new ArrayList<>();
        for (String record : records) {
            table.add(parseRecord(record, delimiter));
        }

        List<String> header = table.get(0);
        if (header.size() < 2) {
            throw new IOException("Header parsed as a single column using delimiter '" + delimiter
                    + "'. Header was: " + physicalLines.get(0));
        }

        Files.createDirectories(outputDir);

        List<Path> written = new ArrayList<>();
        // Column 0 holds the attribute names; every further column is one record.
        for (int col = 1; col < header.size(); col++) {
            Map<String, Object> data = new LinkedHashMap<>();
            Map<String, Object> group = new LinkedHashMap<>();
            boolean inGroup = false;

            for (int rowIdx = 1; rowIdx < table.size(); rowIdx++) {
                List<String> row = table.get(rowIdx);

                String attribute = cleanAttributeName(cell(row, 0));
                if (attribute.isEmpty()) {
                    continue;
                }

                String value = cell(row, col);

                // The end marker closes the group and is itself top level again.
                if (inGroup && attribute.equals(GROUP_END_ATTRIBUTE)) {
                    inGroup = false;
                }

                boolean isTrigger = attribute.equals(GROUP_TRIGGER_ATTRIBUTE);

                // Decide on the raw value, before the omission rules can drop it.
                boolean opensGroup = isTrigger
                        && (GROUP_TRIGGER_VALUE == null || GROUP_TRIGGER_VALUE.equalsIgnoreCase(value.trim()));

                if (!isOmittedValue(value)) {
                    // The trigger itself stays top level; grouping starts after it.
                    Map<String, Object> target = (inGroup && !isTrigger) ? group : data;
                    Object previous = target.put(attribute, value.trim());
                    if (previous != null) {
                        System.err.println("Warning: duplicate attribute '" + attribute
                                + "' in column '" + header.get(col) + "' - keeping the last value.");
                    }
                }

                if (opensGroup) {
                    inGroup = true;
                    // Reserve the group's slot here so it appears in source order.
                    data.put(GROUP_NAME, group);
                }
            }

            if (group.isEmpty()) {
                data.remove(GROUP_NAME);
            }

            Path target = outputDir.resolve(toFileName(header.get(col)) + ".json");
            Files.write(target, toJson(data).getBytes(StandardCharsets.UTF_8));
            written.add(target);
        }

        return written;
    }

    // ------------------------------------------------------------------
    // reading / locating
    // ------------------------------------------------------------------

    private static String readResource(String name) throws IOException {
        ClassLoader loader = CsvToJsonGenerator.class.getClassLoader();
        try (InputStream in = loader.getResourceAsStream(name)) {
            if (in == null) {
                throw new IOException("Resource '" + name + "' not found on the classpath. "
                        + "Expected it at src/main/resources/" + name);
            }
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[8192];
            try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                int read;
                while ((read = reader.read(buf)) != -1) {
                    sb.append(buf, 0, read);
                }
            }
            // Strip a UTF-8 BOM if the export carries one.
            if (sb.length() > 0 && sb.charAt(0) == '﻿') {
                sb.deleteCharAt(0);
            }
            return sb.toString();
        }
    }

    /**
     * Prefers the source tree (src/main/resources/testfiles) so the generated files
     * land next to the CSV and survive a clean build; falls back to the classpath
     * output folder when that layout is not present.
     *
     * <p>Override with {@code -Dcsv2json.out=/some/path}.
     */
    private static Path resolveOutputDirectory() {
        String override = System.getProperty("csv2json.out");
        if (override != null && !override.isEmpty()) {
            return Paths.get(override);
        }

        Path sourceResources = Paths.get("src", "main", "resources");
        if (Files.isDirectory(sourceResources)) {
            return sourceResources.resolve(OUTPUT_FOLDER);
        }

        URL root = CsvToJsonGenerator.class.getClassLoader().getResource(INPUT_RESOURCE);
        if (root != null && "file".equals(root.getProtocol())) {
            try {
                return Paths.get(root.toURI()).getParent().resolve(OUTPUT_FOLDER);
            } catch (URISyntaxException e) {
                throw new UncheckedIOException(new IOException("Could not resolve resource path", e));
            }
        }

        return Paths.get(OUTPUT_FOLDER);
    }

    // ------------------------------------------------------------------
    // CSV normalization
    // ------------------------------------------------------------------

    private static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\r?\n", -1)) {
            lines.add(line);
        }
        // Drop trailing blank lines only - blank lines in the middle may belong to a
        // broken record and are handled during stitching.
        while (!lines.isEmpty() && lines.get(lines.size() - 1).trim().isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    private static char detectDelimiter(String header) throws IOException {
        char best = 0;
        int bestCount = 0;
        for (char candidate : DELIMITER_CANDIDATES) {
            int count = 0;
            for (int i = 0; i < header.length(); i++) {
                if (header.charAt(i) == candidate) {
                    count++;
                }
            }
            if (count > bestCount) {
                bestCount = count;
                best = candidate;
            }
        }
        if (bestCount == 0) {
            throw new IOException("Could not detect a delimiter in the header line: " + header);
        }
        return best;
    }

    /**
     * Joins physical lines until each record holds as many fields as the header and
     * does not end inside an open quote.
     */
    private static List<String> stitchBrokenRecords(List<String> lines, char delimiter) {
        int expectedFields = scan(lines.get(0), delimiter).fields;

        List<String> records = new ArrayList<>();
        StringBuilder buffer = null;
        int repairs = 0;

        for (String line : lines) {
            if (buffer == null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                buffer = new StringBuilder(line);
            } else {
                // A newline inside a quoted field is legitimate content; keep it.
                // A newline in an unterminated record is export damage; remove it.
                buffer.append(scan(buffer.toString(), delimiter).inQuotes ? "\n" : JOIN_WITH).append(line);
                repairs++;
            }

            Scan state = scan(buffer.toString(), delimiter);
            if (!state.inQuotes && state.fields >= expectedFields) {
                if (state.fields > expectedFields) {
                    System.err.println("Warning: record has " + state.fields + " fields, expected "
                            + expectedFields + ": " + preview(buffer.toString()));
                }
                records.add(buffer.toString());
                buffer = null;
            }
        }

        if (buffer != null) {
            System.err.println("Warning: last record is incomplete: " + preview(buffer.toString()));
            records.add(buffer.toString());
        }

        if (repairs > 0) {
            System.out.println("Repaired " + repairs + " stray line break(s).");
        }
        return records;
    }

    private static String preview(String s) {
        return s.length() <= 40 ? s : s.substring(0, 40) + "...";
    }

    /** Result of a quote-aware scan over a partial record. */
    private static final class Scan {
        final int fields;
        final boolean inQuotes;

        Scan(int fields, boolean inQuotes) {
            this.fields = fields;
            this.inQuotes = inQuotes;
        }
    }

    private static Scan scan(String text, char delimiter) {
        boolean inQuotes = false;
        int fields = 1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        i++; // escaped ""
                    } else {
                        inQuotes = false;
                    }
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == delimiter) {
                fields++;
            }
        }
        return new Scan(fields, inQuotes);
    }

    /** Splits one complete record into fields, honouring quotes and "" escapes. */
    private static List<String> parseRecord(String record, char delimiter) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < record.length(); i++) {
            char c = record.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < record.length() && record.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == delimiter) {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields;
    }

    private static String cell(List<String> row, int index) {
        return index < row.size() ? row.get(index) : "";
    }

    // ------------------------------------------------------------------
    // attribute / value normalization
    // ------------------------------------------------------------------

    /** Removes "(...)" groups (nested included), collapses whitespace, trims. */
    static String cleanAttributeName(String name) {
        String result = name;
        String previous;
        do {
            previous = result;
            result = INNERMOST_BRACKETS.matcher(result).replaceAll(" ");
        } while (!result.equals(previous));

        result = UNCLOSED_BRACKET.matcher(result).replaceAll(" ");
        result = WHITESPACE_RUN.matcher(result).replaceAll(" ");
        return result.trim();
    }

    /** True when the value should not produce a JSON property at all. */
    static boolean isOmittedValue(String value) {
        if (value == null) {
            return true;
        }
        String v = value.trim();
        if (v.isEmpty()) {
            return true;
        }
        return NOT_AVAILABLE.matcher(v).find() || LEER.matcher(v).matches();
    }

    /** Whitespace becomes '_', path-hostile characters become '_'. */
    static String toFileName(String columnHeader) {
        String name = cleanAttributeName(columnHeader);
        name = INVALID_FILENAME_CHARS.matcher(name).replaceAll("_");
        name = WHITESPACE_RUN.matcher(name).replaceAll("_");
        name = name.replace(' ', '_').trim();
        return name.isEmpty() ? "unnamed" : name;
    }

    // ------------------------------------------------------------------
    // JSON output
    // ------------------------------------------------------------------

    private static String toJson(Map<String, Object> data) {
        StringBuilder sb = new StringBuilder();
        writeObject(sb, data, 1);
        return sb.append('\n').toString();
    }

    /** Writes a JSON object whose values are either Strings or nested Maps. */
    @SuppressWarnings("unchecked")
    private static void writeObject(StringBuilder sb, Map<String, Object> map, int depth) {
        String pad = repeat("  ", depth);
        String closePad = repeat("  ", depth - 1);

        sb.append("{\n");
        int i = 0;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            sb.append(pad).append('"').append(escape(entry.getKey())).append("\": ");

            Object value = entry.getValue();
            if (value instanceof Map) {
                writeObject(sb, (Map<String, Object>) value, depth + 1);
            } else {
                sb.append('"').append(escape(String.valueOf(value))).append('"');
            }

            if (++i < map.size()) {
                sb.append(',');
            }
            sb.append('\n');
        }
        sb.append(closePad).append('}');
    }

    private static String repeat(String s, int times) {
        StringBuilder sb = new StringBuilder(s.length() * times);
        for (int i = 0; i < times; i++) {
            sb.append(s);
        }
        return sb.toString();
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                case '\b': sb.append("\\b");  break;
                case '\f': sb.append("\\f");  break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}
