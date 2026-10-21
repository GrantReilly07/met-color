package com.metcolor;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

public class ArtworkCsvReaderTest {
    @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void readsBomReorderedColumnsLargeFieldsAndQuotedMultilineRecords() throws Exception {
        Path csv = temporaryFolder.newFile("quoted.csv").toPath();
        Files.writeString(csv, "\uFEFFtitle,primaryImage,objectID,primaryImageSmall\r\n"
                + "\"A comma, an escaped \"\"quote\"\" and\r\na newline " + "x".repeat(200000)
                + "\",https://example.org/original, 12 ,\"https://example.org/small?a=1,b=2\"\r\n"
                + "\r\nEmpty,,13,");
        try (ArtworkCsvReader reader = new ArtworkCsvReader(csv)) {
            assertEquals(new ArtworkCsvReader.Artwork("12", "https://example.org/small?a=1,b=2",
                    "https://example.org/original"), reader.next());
            assertEquals(new ArtworkCsvReader.Artwork("13", "", ""), reader.next());
            assertNull(reader.next());
        }
    }

    @Test
    public void rejectsTruncatedQuotedRecords() throws Exception {
        Path csv = temporaryFolder.newFile("truncated.csv").toPath();
        Files.writeString(csv, "objectID,primaryImageSmall\n1,\"unfinished\n");
        try (ArtworkCsvReader reader = new ArtworkCsvReader(csv)) {
            try { reader.next(); fail("Expected an unclosed quote error"); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("Unclosed quote")); }
        }
    }

    @Test
    public void rejectsIncorrectColumnCounts() throws Exception {
        Path csv = temporaryFolder.newFile("columns.csv").toPath();
        Files.writeString(csv, "objectID,primaryImageSmall\n1,image,extra\n");
        try (ArtworkCsvReader reader = new ArtworkCsvReader(csv)) {
            try { reader.next(); fail("Expected a column count error"); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("3 columns; expected 2")); }
        }
    }
}
