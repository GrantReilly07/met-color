package com.metcolor;

import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpMethod;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import com.microsoft.azure.functions.HttpStatusType;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.Assert.*;

public class ClosestColorFunctionTest {
    @Test
    public void findsExactAndNearestMatchesUsingAllThreeChannels() throws Exception {
        var black = new ClosestColorFunction.ArtworkColor(10, 0, 0, 0);
        var white = new ClosestColorFunction.ArtworkColor(20, 255, 255, 255);
        var blue = new ClosestColorFunction.ArtworkColor(30, 100, 150, 200);
        var colors = List.of(black, white, blue);
        assertEquals(new ClosestColorFunction.Match(blue, 0), ClosestColorFunction.closest(colors, 100, 150, 200));
        assertEquals(new ClosestColorFunction.Match(blue, 150), ClosestColorFunction.closest(colors, 105, 145, 190));
        assertEquals(195075, ClosestColorFunction.closest(List.of(white), 0, 0, 0).distanceSquared());
    }

    @Test
    public void breaksEqualDistanceTiesByLowestObjectId() throws Exception {
        var a = new ClosestColorFunction.ArtworkColor(9, 1, 0, 0);
        var b = new ClosestColorFunction.ArtworkColor(3, 0, 1, 0);
        assertEquals(b, ClosestColorFunction.closest(List.of(a, b), 0, 0, 0).artwork());
        assertEquals(b, ClosestColorFunction.closest(List.of(b, a), 0, 0, 0).artwork());
    }

    @Test
    public void loadsAndValidatesCsvAndReturnsAnImmutableCatalog() throws Exception {
        var colors = load("\uFEFFobjectID,red,green,blue\n1, 0, 255, 20\n\n2,50,60,70\n");
        assertEquals(List.of(new ClosestColorFunction.ArtworkColor(1, 0, 255, 20),
                new ClosestColorFunction.ArtworkColor(2, 50, 60, 70)), colors);
        try { colors.clear(); fail("Catalog must be immutable"); }
        catch (UnsupportedOperationException expected) { }
        for (String csv : List.of("", "wrong header\n", "objectID,red,green,blue\n",
                "objectID,red,green,blue\n1,256,0,0\n", "objectID,red,green,blue\n1,0,0\n",
                "objectID,red,green,blue\n0,0,0,0\n", "objectID,red,green,blue\n1,red,0,0\n")) {
            try { load(csv); fail("Invalid catalog must fail: " + csv); }
            catch (IOException expected) { }
        }
    }

    @Test
    public void endpointReturnsJsonMatchAndArtworkLink() {
        var function = new ClosestColorFunction(() -> List.of(
                new ClosestColorFunction.ArtworkColor(123, 100, 150, 200),
                new ClosestColorFunction.ArtworkColor(456, 255, 255, 255)));
        HttpResponseMessage response = function.run(request(Map.of("red", "105", "green", "145", "blue", "190")), context());
        assertEquals(200, response.getStatusCode());
        assertEquals("application/json", response.getHeader("Content-Type"));
        assertEquals(Map.of("objectID", 123, "red", 100, "green", 150, "blue", 200,
                "distanceSquared", 150, "objectURL", "https://www.metmuseum.org/art/collection/search/123"), response.getBody());
    }

    @Test
    public void endpointRejectsMissingNonIntegerAndOutOfRangeInputsBeforeLoadingData() {
        AtomicInteger loads = new AtomicInteger();
        var function = new ClosestColorFunction(() -> {
            loads.incrementAndGet();
            throw new IOException("Should not load");
        });
        List<Map<String, String>> inputs = List.of(Map.of(), Map.of("red", "1", "green", "2"),
                Map.of("red", "-1", "green", "0", "blue", "0"),
                Map.of("red", "256", "green", "0", "blue", "0"),
                Map.of("red", "1.5", "green", "0", "blue", "0"),
                Map.of("red", "0", "green", "text", "blue", "0"),
                Map.of("red", "0", "green", "0", "blue", "99999999999999999"));
        for (Map<String, String> input : inputs) {
            HttpResponseMessage response = function.run(request(input), context());
            assertEquals(400, response.getStatusCode());
            assertTrue(((Map<?, ?>) response.getBody()).get("error").toString().contains("between 0 and 255"));
        }
        assertEquals(0, loads.get());
    }

    @Test
    public void endpointReportsUnavailableDataWithoutExposingInternalErrors() {
        var function = new ClosestColorFunction(() -> { throw new IOException("Internal file path"); });
        HttpResponseMessage response = function.run(request(Map.of("red", "0", "green", "0", "blue", "0")), context());
        assertEquals(503, response.getStatusCode());
        assertEquals(Map.of("error", "Artwork color data is unavailable."), response.getBody());
    }

    @Test
    public void defaultEndpointUsesThePackagedColorCsv() throws Exception {
        ClosestColorFunction.ArtworkColor sample;
        try (InputStream input = getClass().getResourceAsStream("/objectAverageColors.csv")) {
            assertNotNull("The build must package the color CSV", input);
            sample = ClosestColorFunction.loadColors(input).get(0);
        }
        var function = new ClosestColorFunction();
        HttpResponseMessage response = function.run(request(Map.of("red", "" + sample.red(),
                "green", "" + sample.green(), "blue", "" + sample.blue())), context());
        assertEquals(200, response.getStatusCode());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals(0, body.get("distanceSquared"));
        assertEquals(sample.red(), body.get("red"));
        assertEquals(sample.green(), body.get("green"));
        assertEquals(sample.blue(), body.get("blue"));
    }

    private static List<ClosestColorFunction.ArtworkColor> load(String csv) throws IOException {
        return ClosestColorFunction.loadColors(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
    }

    private static ExecutionContext context() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setLevel(Level.OFF);
        return new ExecutionContext() {
            public Logger getLogger() { return logger; }
            public String getInvocationId() { return "test"; }
            public String getFunctionName() { return "closestColor"; }
        };
    }

    private static HttpRequestMessage<Optional<String>> request(Map<String, String> query) {
        return new HttpRequestMessage<>() {
            public URI getUri() { return URI.create("http://localhost:7071/api/closest-color"); }
            public HttpMethod getHttpMethod() { return HttpMethod.GET; }
            public Map<String, String> getHeaders() { return Map.of(); }
            public Map<String, String> getQueryParameters() { return query; }
            public Optional<String> getBody() { return Optional.empty(); }
            public HttpResponseMessage.Builder createResponseBuilder(HttpStatus status) { return new ResponseBuilder(status); }
            public HttpResponseMessage.Builder createResponseBuilder(HttpStatusType status) { return new ResponseBuilder(status); }
        };
    }

    private static final class ResponseBuilder implements HttpResponseMessage.Builder {
        private HttpStatusType status;
        private final Map<String, String> headers = new HashMap<>();
        private Object body;
        ResponseBuilder(HttpStatusType status) { this.status = status; }
        public HttpResponseMessage.Builder status(HttpStatusType status) { this.status = status; return this; }
        public HttpResponseMessage.Builder header(String key, String value) { headers.put(key, value); return this; }
        public HttpResponseMessage.Builder body(Object body) { this.body = body; return this; }
        public HttpResponseMessage build() {
            return new HttpResponseMessage() {
                public HttpStatusType getStatus() { return status; }
                public String getHeader(String key) { return headers.get(key); }
                public Object getBody() { return body; }
            };
        }
    }
}
