package com.metcolor;

import com.sksamuel.scrimage.ImmutableImage;
import com.sksamuel.scrimage.color.RGBColor;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/** Joins usableObjectIDS.txt to openaccess.csv and averages the selected images. */
public class avgColorGenerator {
    private static final String CSV_HEADER = "objectID,red,green,blue";
    private static final int WORKERS = 8;
    private static final int MAX_PENDING = WORKERS * 2;
    private static final int MAX_ATTEMPTS = 3;

    public static void main(String[] args) throws IOException, InterruptedException {
        if (args.length != 0) throw new IllegalArgumentException("Usage: avgColorGenerator (no arguments)");
        Path directory = Files.isRegularFile(Path.of("openaccess.csv")) ? Path.of("") : Path.of("met-color");
        Path output = directory.resolve("objectAverageColors.csv");
        int saved = generate(directory.resolve("usableObjectIDS.txt"), directory.resolve("openaccess.csv"), output);
        System.out.println("Saved " + saved + " new average colors to " + output.toAbsolutePath());
    }

    static int generate(Path idsFile, Path artworkCsv, Path output) throws IOException, InterruptedException {
        Set<String> remaining = readIds(idsFile);
        Set<String> savedIds = readSavedIds(output);
        if (!savedIds.isEmpty()) System.out.println("Resuming: skipping " + savedIds.size() + " already-saved IDs.");
        RunStats stats = new RunStats();
        DownloadStats downloads = new DownloadStats();
        try (ArtworkCsvReader artworks = new ArtworkCsvReader(artworkCsv);
             HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
                     .followRedirects(HttpClient.Redirect.NORMAL).build();
             BufferedWriter writer = openForAppend(output, CSV_HEADER);
             BufferedWriter failures = openForAppend(failureFile(output), "objectID,error");
             ExecutorService workers = Executors.newFixedThreadPool(WORKERS)) {
            CompletionService<Result> results = new ExecutorCompletionService<>(workers);
            int pending = 0;
            boolean end = false;
            try {
                while (!end || pending > 0) {
                    // Stream the 300 MB CSV, retaining at most sixteen pending image jobs.
                    while (!end && pending < MAX_PENDING) {
                        if (remaining.isEmpty()) { end = true; break; }
                        ArtworkCsvReader.Artwork artwork = artworks.next();
                        if (artwork == null) { end = true; break; }
                        if (!remaining.remove(artwork.objectID())) continue;
                        stats.matched++;
                        if (savedIds.contains(artwork.objectID())) { stats.alreadySaved++; continue; }
                        if (artwork.smallImage().isEmpty()) {
                            stats.noImage++;
                            stats.processed++;
                            stats.report(downloads, false);
                            continue;
                        }
                        results.submit(() -> processImage(client, artwork, downloads));
                        pending++;
                    }
                    if (pending == 0) continue;
                    Future<Result> completed = results.poll(1, TimeUnit.SECONDS);
                    if (completed == null) { stats.report(downloads, false); continue; }
                    Result result;
                    try {
                        result = completed.get();
                    } catch (ExecutionException e) {
                        if (e.getCause() instanceof InterruptedException) {
                            throw new InterruptedException("Image worker interrupted");
                        }
                        if (e.getCause() instanceof Error fatal) throw fatal;
                        throw new IOException("Image worker failed", e.getCause());
                    }
                    pending--;
                    stats.processed++;
                    // Only this thread writes CSV rows. Persist each result immediately.
                    if (result.error() == null) {
                        writer.write(result.row());
                        writer.newLine();
                        writer.flush();
                        stats.saved++;
                    } else {
                        stats.failed++;
                        failures.write(result.objectID() + ",\"" + result.error().replace("\"", "\"\"")
                                .replace('\n', ' ').replace('\r', ' ') + "\"");
                        failures.newLine();
                        failures.flush();
                        System.err.println("Failed object " + result.objectID() + ": " + result.error());
                    }
                    stats.report(downloads, false);
                }
            } finally {
                workers.shutdownNow();
            }
        }
        stats.report(downloads, true);
        System.out.printf("CSV selection: %,d matched; %,d already saved; %,d requested IDs absent from openaccess.csv.%n",
                stats.matched, stats.alreadySaved, remaining.size());
        if (stats.failed > 0) System.err.println("Failures appended to " + failureFile(output).toAbsolutePath()
                + " (this log also retains errors from earlier runs).");
        return stats.saved;
    }

    private record Result(String objectID, String row, String error) { }

    private static Result processImage(HttpClient client, ArtworkCsvReader.Artwork artwork, DownloadStats stats)
            throws InterruptedException {
        String stage = "image download";
        try {
            byte[] bytes;
            try {
                bytes = download(client, artwork.smallImage(), stats);
            } catch (HttpStatusException e) {
                if (e.status != 404 || artwork.originalImage().isEmpty()
                        || artwork.originalImage().equals(artwork.smallImage())) throw e;
                stage = "original image download";
                System.err.println("Object " + artwork.objectID()
                        + ": small image returned HTTP 404; trying primaryImage from CSV");
                bytes = download(client, artwork.originalImage(), stats);
            }
            stage = "image decoding";
            ImmutableImage image;
            long started = System.nanoTime();
            try { image = ImmutableImage.loader().fromBytes(bytes); }
            finally { stats.decodeNanos.add(System.nanoTime() - started); }
            stage = "image averaging";
            RGBColor color;
            started = System.nanoTime();
            try { color = average(image); }
            finally { stats.averageNanos.add(System.nanoTime() - started); }
            return new Result(artwork.objectID(), artwork.objectID() + "," + color.red + ","
                    + color.green + "," + color.blue, null);
        } catch (IOException | RuntimeException e) {
            return new Result(artwork.objectID(), null, stage + ": " + e);
        }
    }

    private static byte[] download(HttpClient client, String url, DownloadStats stats)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60)).GET().build();
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            HttpResponse<byte[]> response = null;
            IOException networkError = null;
            long started = System.nanoTime();
            stats.requests.increment();
            try { response = client.send(request, HttpResponse.BodyHandlers.ofByteArray()); }
            catch (IOException e) { networkError = e; }
            finally { stats.downloadNanos.add(System.nanoTime() - started); }
            if (response != null && response.statusCode() == 200) return response.body();
            int status = response == null ? 0 : response.statusCode();
            boolean retryable = networkError != null || status == 403 || status == 429 || (status >= 500 && status <= 599);
            if (!retryable || attempt == MAX_ATTEMPTS) {
                if (networkError != null) throw new IOException("Download failed for " + url, networkError);
                throw new HttpStatusException(status, url);
            }
            Duration delay = Duration.ofSeconds(1L << (attempt - 1));
            if (response != null) {
                Duration requested = retryAfter(response);
                if (requested.compareTo(delay) > 0) delay = requested;
            }
            stats.retries.increment();
            System.err.println("Retrying image " + url + " (attempt " + (attempt + 1) + "/" + MAX_ATTEMPTS + ")");
            started = System.nanoTime();
            try { Thread.sleep(delay); }
            finally { stats.retryNanos.add(System.nanoTime() - started); }
        }
        throw new IOException("Retry limit reached for " + url);
    }

    private static Duration retryAfter(HttpResponse<?> response) {
        String value = response.headers().firstValue("Retry-After").orElse("").trim();
        try {
            Duration delay = value.matches("[0-9]+") ? Duration.ofSeconds(Long.parseLong(value))
                    : Duration.between(Instant.now(), ZonedDateTime.parse(value,
                            DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
            return delay.isNegative() ? Duration.ZERO : delay;
        } catch (NumberFormatException | DateTimeParseException e) { return Duration.ZERO; }
    }

    private static final class HttpStatusException extends IOException {
        final int status;
        HttpStatusException(int status, String url) { super("HTTP " + status + " for " + url); this.status = status; }
    }

    private static final class DownloadStats {
        final LongAdder requests = new LongAdder(), retries = new LongAdder(), downloadNanos = new LongAdder(),
                decodeNanos = new LongAdder(), averageNanos = new LongAdder(), retryNanos = new LongAdder();
    }

    private static final class RunStats {
        int processed, saved, failed, noImage, matched, alreadySaved;
        final long started = System.nanoTime();
        long lastReport = started;
        int nextReportAt = 100;
        void report(DownloadStats downloads, boolean force) {
            long now = System.nanoTime();
            if (!force && processed < nextReportAt && now - lastReport < TimeUnit.SECONDS.toNanos(10)) return;
            lastReport = now;
            nextReportAt = processed + 100;
            System.out.printf("Processed %,d IDs; saved %,d colors; no image %,d; failed %,d; %.2f IDs/sec%n",
                    processed, saved, noImage, failed, processed / Math.max((now - started) / 1e9, .001));
            System.out.printf("Timing totals (overlapping work): downloads %.2fs (%d requests); decode %.2fs; "
                            + "average %.2fs; retry waits %.2fs; retries %d%n",
                    downloads.downloadNanos.sum() / 1e9, downloads.requests.sum(), downloads.decodeNanos.sum() / 1e9,
                    downloads.averageNanos.sum() / 1e9, downloads.retryNanos.sum() / 1e9, downloads.retries.sum());
        }
    }

    private static Set<String> readIds(Path input) throws IOException {
        Set<String> ids = new HashSet<>();
        try (BufferedReader reader = Files.newBufferedReader(input, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String id = line.trim();
                if (id.isEmpty()) continue;
                if (!id.matches("[0-9]+")) throw new IOException("Invalid object ID in " + input + ": " + id);
                ids.add(id);
            }
        }
        return ids;
    }

    private static Set<String> readSavedIds(Path output) throws IOException {
        Set<String> saved = new HashSet<>();
        if (!Files.exists(output) || Files.size(output) == 0) return saved;
        try (BufferedReader reader = Files.newBufferedReader(output, StandardCharsets.UTF_8)) {
            if (!CSV_HEADER.equals(reader.readLine())) throw new IOException("Unexpected CSV header; refusing to modify " + output);
            String row;
            while ((row = reader.readLine()) != null) {
                String[] fields = row.split(",", -1);
                if (fields.length != 4 || !fields[0].matches("[0-9]+")) throw new IOException("Invalid saved row in " + output + ": " + row);
                for (int i = 1; i < fields.length; i++) {
                    if (!fields[i].matches("[0-9]{1,3}") || Integer.parseInt(fields[i]) > 255) {
                        throw new IOException("Invalid saved color in " + output + ": " + row);
                    }
                }
                saved.add(fields[0]);
            }
        }
        return saved;
    }

    static BufferedWriter openForAppend(Path path, String header) throws IOException {
        boolean empty = !Files.exists(path) || Files.size(path) == 0;
        boolean needsNewline = false;
        if (!empty) {
            try (SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.READ)) {
                ByteBuffer lastByte = ByteBuffer.allocate(1);
                channel.position(channel.size() - 1);
                channel.read(lastByte);
                needsNewline = lastByte.array()[0] != '\n';
            }
        }
        BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        try {
            if (empty) { writer.write(header); writer.newLine(); }
            else if (needsNewline) writer.newLine();
            writer.flush();
            return writer;
        } catch (IOException e) { writer.close(); throw e; }
    }

    static Path failureFile(Path output) {
        return output.resolveSibling(output.getFileName().toString().replaceFirst("\\.csv$", "") + "-failures.csv");
    }

    static RGBColor average(ImmutableImage image) {
        // Scrimage's image.average() blends alpha instead of computing arithmetic channel means.
        int[] pixels = image.argbints();
        long red = 0, green = 0, blue = 0;
        for (int pixel : pixels) {
            red += (pixel >>> 16) & 0xFF;
            green += (pixel >>> 8) & 0xFF;
            blue += pixel & 0xFF;
        }
        return new RGBColor((int) Math.round((double) red / pixels.length),
                (int) Math.round((double) green / pixels.length), (int) Math.round((double) blue / pixels.length), 255);
    }
}
