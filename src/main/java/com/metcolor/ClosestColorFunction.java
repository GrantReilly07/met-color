package com.metcolor;

import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpMethod;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import com.microsoft.azure.functions.annotation.AuthorizationLevel;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.HttpTrigger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;

/** Azure HTTP endpoint for an exact nearest-color search over the packaged CSV. */
public class ClosestColorFunction {
    record ArtworkColor(int objectID, int red, int green, int blue) { }
    record Match(ArtworkColor artwork, int distanceSquared) { }
    @FunctionalInterface interface ColorSource { List<ArtworkColor> get() throws IOException; }

    private static volatile List<ArtworkColor> cachedColors;
    private final ColorSource source;

    public ClosestColorFunction() { this(ClosestColorFunction::packagedColors); }
    ClosestColorFunction(ColorSource source) { this.source = source; }

    @FunctionName("closestColor")
    public HttpResponseMessage run(
            @HttpTrigger(name = "request", methods = {HttpMethod.GET},
                    authLevel = AuthorizationLevel.ANONYMOUS, route = "closest-color")
            HttpRequestMessage<Optional<String>> request,
            ExecutionContext context) {
        int red, green, blue;
        try {
            Map<String, String> query = request.getQueryParameters();
            red = channel(query, "red");
            green = channel(query, "green");
            blue = channel(query, "blue");
        } catch (IllegalArgumentException e) {
            return request.createResponseBuilder(HttpStatus.BAD_REQUEST)
                    .header("Content-Type", "application/json")
                    .body(Map.of("error", e.getMessage())).build();
        }

        try {
            Match match = closest(source.get(), red, green, blue);
            ArtworkColor color = match.artwork();
            return request.createResponseBuilder(HttpStatus.OK)
                    .header("Content-Type", "application/json")
                    .body(Map.of(
                            "objectID", color.objectID(),
                            "red", color.red(), "green", color.green(), "blue", color.blue(),
                            "distanceSquared", match.distanceSquared(),
                            "objectURL", "https://www.metmuseum.org/art/collection/search/" + color.objectID()))
                    .build();
        } catch (IOException e) {
            context.getLogger().log(Level.SEVERE, "Cannot load artwork color data", e);
            return request.createResponseBuilder(HttpStatus.SERVICE_UNAVAILABLE)
                    .header("Content-Type", "application/json")
                    .body(Map.of("error", "Artwork color data is unavailable.")).build();
        }
    }

    private static int channel(Map<String, String> query, String name) {
        String value = query.get(name);
        try {
            int channel = Integer.parseInt(value == null ? "" : value.trim());
            if (channel >= 0 && channel <= 255) return channel;
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException(name + " must be an integer between 0 and 255.");
    }

    private static List<ArtworkColor> packagedColors() throws IOException {
        List<ArtworkColor> colors = cachedColors;
        if (colors == null) {
            synchronized (ClosestColorFunction.class) {
                colors = cachedColors;
                if (colors == null) {
                    try (InputStream input = ClosestColorFunction.class.getResourceAsStream("/objectAverageColors.csv")) {
                        if (input == null) throw new IOException("Missing packaged objectAverageColors.csv");
                        colors = loadColors(input);
                        cachedColors = colors;
                    }
                }
            }
        }
        return colors;
    }

    static List<ArtworkColor> loadColors(InputStream input) throws IOException {
        List<ArtworkColor> colors = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (header != null && header.startsWith("\uFEFF")) header = header.substring(1);
            if (!"objectID,red,green,blue".equals(header)) throw new IOException("Invalid artwork color CSV header");
            int lineNumber = 1;
            String row;
            while ((row = reader.readLine()) != null) {
                lineNumber++;
                if (row.isBlank()) continue;
                String[] fields = row.split(",", -1);
                try {
                    if (fields.length != 4) throw new NumberFormatException("Expected four fields");
                    int id = Integer.parseInt(fields[0].trim());
                    int red = Integer.parseInt(fields[1].trim());
                    int green = Integer.parseInt(fields[2].trim());
                    int blue = Integer.parseInt(fields[3].trim());
                    if (id <= 0 || red < 0 || red > 255 || green < 0 || green > 255 || blue < 0 || blue > 255) {
                        throw new NumberFormatException("Value outside the valid range");
                    }
                    colors.add(new ArtworkColor(id, red, green, blue));
                } catch (NumberFormatException e) {
                    throw new IOException("Invalid artwork color CSV row at line " + lineNumber, e);
                }
            }
        }
        if (colors.isEmpty()) throw new IOException("Artwork color CSV has no records");
        return List.copyOf(colors);
    }

    static Match closest(List<ArtworkColor> colors, int red, int green, int blue) throws IOException {
        ArtworkColor best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (ArtworkColor color : colors) {
            int dr = red - color.red(), dg = green - color.green(), db = blue - color.blue();
            int distance = dr * dr + dg * dg + db * db;
            // Ties use the lowest ID so results don't depend on CSV row ordering.
            if (distance < bestDistance || (distance == bestDistance && color.objectID() < best.objectID())) {
                best = color;
                bestDistance = distance;
            }
        }
        if (best == null) throw new IOException("No artwork colors available");
        return new Match(best, bestDistance);
    }
}
