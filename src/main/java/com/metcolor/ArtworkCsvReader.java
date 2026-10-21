package com.metcolor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PushbackReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Streams CSV records, including quoted commas, escaped quotes, and embedded newlines. */
final class ArtworkCsvReader implements AutoCloseable {
    record Artwork(String objectID, String smallImage, String originalImage) { }

    private final PushbackReader reader;
    private final int columns;
    private final int idColumn;
    private final int smallColumn;
    private final int originalColumn;
    private long recordNumber;

    ArtworkCsvReader(Path path) throws IOException {
        reader = new PushbackReader(new BufferedReader(
                Files.newBufferedReader(path, StandardCharsets.UTF_8), 1024 * 1024), 1);
        try {
            int first = reader.read();
            if (first != -1 && first != '\uFEFF') reader.unread(first);
            List<String> header = readRecord();
            if (header == null) throw new IOException("Empty artwork CSV: " + path);
            columns = header.size();
            idColumn = header.indexOf("objectID");
            smallColumn = header.indexOf("primaryImageSmall");
            originalColumn = header.indexOf("primaryImage");
            if (idColumn < 0 || smallColumn < 0) {
                throw new IOException("Artwork CSV must contain objectID and primaryImageSmall columns: " + path);
            }
        } catch (IOException | RuntimeException e) {
            reader.close();
            throw e;
        }
    }

    Artwork next() throws IOException {
        List<String> fields;
        while ((fields = readRecord()) != null) {
            if (fields.size() == 1 && fields.get(0).isBlank()) continue;
            if (fields.size() != columns) {
                throw new IOException("CSV record " + recordNumber + " has " + fields.size()
                        + " columns; expected " + columns);
            }
            return new Artwork(fields.get(idColumn).trim(), fields.get(smallColumn).trim(),
                    originalColumn < 0 ? "" : fields.get(originalColumn).trim());
        }
        return null;
    }

    private List<String> readRecord() throws IOException {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false, closedQuote = false, hasData = false;
        int value;
        while ((value = reader.read()) != -1) {
            hasData = true;
            char c = (char) value;
            if (quoted) {
                if (c == '"') {
                    int next = reader.read();
                    if (next == '"') field.append('"');
                    else {
                        quoted = false;
                        closedQuote = true;
                        if (next != -1) reader.unread(next);
                    }
                } else field.append(c);
            } else if (c == ',') {
                fields.add(field.toString());
                field.setLength(0);
                closedQuote = false;
            } else if (c == '\n' || c == '\r') {
                if (c == '\r') {
                    int next = reader.read();
                    if (next != -1 && next != '\n') reader.unread(next);
                }
                fields.add(field.toString());
                recordNumber++;
                return fields;
            } else if (c == '"' && field.length() == 0 && !closedQuote) {
                quoted = true;
            } else if (c == '"' || closedQuote) {
                throw new IOException("Invalid quoting in CSV record " + (recordNumber + 1));
            } else field.append(c);
        }
        if (quoted) throw new IOException("Unclosed quote in CSV record " + (recordNumber + 1));
        if (!hasData) return null;
        fields.add(field.toString());
        recordNumber++;
        return fields;
    }

    @Override public void close() throws IOException { reader.close(); }
}
