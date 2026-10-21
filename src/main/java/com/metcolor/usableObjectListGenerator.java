package com.metcolor;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Set;

/**
 * Reads the MetObjects.txt file and generates a list of object IDs
 * for objects that are 2D mediums.
 */
public class usableObjectListGenerator {

    private static final Set<String> VALID_CLASSIFICATIONS = Set.of(
            "Paintings",
            "Photographs",
            "Drawings",
            "Prints",
            "Textiles"
    );

    public static void main(String[] args) {

        String fileName = "MetColor\\MetObjects.txt";
        String outputFileName = "usableObjectIDS";

        try (BufferedReader reader = new BufferedReader(
                new FileReader(fileName), 1024 * 1024);
             BufferedWriter writer = new BufferedWriter(
                     new FileWriter(outputFileName))) {

            // Skip header
            reader.readLine();

            String line;

            while ((line = reader.readLine()) != null) {

                String[] fields = getNeededFields(line);

                if (fields == null) {
                    continue;
                }

                String objectID = fields[0];
                String classification = fields[1];

                if (VALID_CLASSIFICATIONS.contains(classification)) {
                    writer.write(objectID);
                    writer.newLine();
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /*
     * Extracts:
     * column 4  -> Object ID
     * column 45 -> Classification
     *
     * handles commas inside quoted CSV fields.
     */
    private static String[] getNeededFields(String line) {

        String objectID = null;
        String classification = null;

        int column = 0;
        int start = 0;

        boolean inQuotes = false;

        for (int i = 0; i <= line.length(); i++) {

            char c = (i < line.length()) ? line.charAt(i) : ',';

            if (c == '"') {

                // Handle escaped quote: ""
                if (inQuotes
                        && i + 1 < line.length()
                        && line.charAt(i + 1) == '"') {

                    i++;

                } else {
                    inQuotes = !inQuotes;
                }

            } else if (c == ',' && !inQuotes) {

                if (column == 4) {
                    objectID = cleanField(
                            line.substring(start, i)
                    );
                }

                if (column == 45) {
                    classification = cleanField(
                            line.substring(start, i)
                    );

                    // We have everything we need.
                    break;
                }

                column++;
                start = i + 1;
            }
        }

        if (objectID == null || classification == null) {
            return null;
        }

        return new String[] {
                objectID,
                classification
        };
    }

    // Removes surrounding quotes and unescapes double quotes inside the field.
    private static String cleanField(String field) {

        if (field.length() >= 2
                && field.charAt(0) == '"'
                && field.charAt(field.length() - 1) == '"') {

            field = field.substring(
                    1,
                    field.length() - 1
            );

            field = field.replace("\"\"", "\"");
        }

        return field;
    }
}
