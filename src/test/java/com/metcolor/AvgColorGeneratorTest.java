package com.metcolor;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class AvgColorGeneratorTest {
    @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private static final class Server implements AutoCloseable {
        final HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final ExecutorService handlers = Executors.newCachedThreadPool();
        Server() throws IOException { http.setExecutor(handlers); }
        String base() { return "http://127.0.0.1:" + http.getAddress().getPort(); }
        void start() { http.start(); }
        @Override public void close() { http.stop(0); handlers.shutdownNow(); }
    }

    private static void respond(HttpExchange exchange, int status, byte[] bytes) throws IOException {
        try {
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        } finally { exchange.close(); }
    }

    private static byte[] image() throws IOException {
        BufferedImage image = new BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0x14283C);
        image.setRGB(1, 0, 0x3C5064);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
    }

    @Test(timeout = 10000)
    public void joinsIdsToCsvDownloadsConcurrentlyAndResumesWithoutApiRequests() throws Exception {
        try (Server server = new Server()) {
            byte[] bytes = image();
            AtomicInteger imageRequests = new AtomicInteger();
            AtomicInteger unexpectedRequests = new AtomicInteger();
            AtomicBoolean overlapped = new AtomicBoolean();
            CountDownLatch twoImages = new CountDownLatch(2);
            server.http.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                if (path.equals("/good")) {
                    int number = imageRequests.incrementAndGet();
                    twoImages.countDown();
                    try {
                        if (number == 1) overlapped.set(twoImages.await(3, TimeUnit.SECONDS));
                    } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    respond(exchange, 200, bytes);
                } else {
                    if (!path.equals("/missing") && !path.equals("/missing-original")) unexpectedRequests.incrementAndGet();
                    respond(exchange, 404, new byte[] { 0 });
                }
            });
            server.start();
            Path ids = temporaryFolder.newFile("ids.txt").toPath();
            Path csv = temporaryFolder.newFile("openaccess.csv").toPath();
            Path output = temporaryFolder.newFile("colors.csv").toPath();
            Files.writeString(ids, "1\n2\n3\n4\n5\n6\n6\n9\n");
            Files.writeString(output, "objectID,red,green,blue\n9,10,20,30");
            String base = server.base();
            Files.writeString(csv, "title,primaryImageSmall,objectID,primaryImage\r\n"
                    + "\"Quoted \"\"title\"\", with\r\nnewlines\"," + base + "/good,1,\r\n"
                    + "Empty,,2," + base + "/unexpected\r\n"
                    + "Fallback," + base + "/missing,3," + base + "/good\r\n"
                    + "Broken," + base + "/missing,4," + base + "/missing-original\r\n"
                    + "Duplicate," + base + "/good,6,\r\n"
                    + "Duplicate again," + base + "/unexpected,6,\r\n"
                    + "Unselected," + base + "/unexpected,7,\r\n"
                    + "Saved," + base + "/unexpected,9,\r\n");
            assertEquals(3, avgColorGenerator.generate(ids, csv, output));
            assertTrue("Image requests must overlap", overlapped.get());
            assertEquals(3, imageRequests.get());
            assertEquals(0, unexpectedRequests.get());
            List<String> rows = Files.readAllLines(output);
            assertEquals(List.of("objectID,red,green,blue", "9,10,20,30"), rows.subList(0, 2));
            assertEquals(List.of("1,40,60,80", "3,40,60,80", "6,40,60,80"), rows.subList(2, 5).stream().sorted().toList());
            List<String> failures = Files.readAllLines(avgColorGenerator.failureFile(output));
            assertEquals(2, failures.size());
            assertTrue(failures.get(1).startsWith("4,"));
            assertTrue(failures.get(1).contains("original image download"));

            Files.writeString(ids, "1\n2\n3\n6\n9\n");
            assertEquals(0, avgColorGenerator.generate(ids, csv, output));
            assertEquals(3, imageRequests.get());
            assertEquals(rows, Files.readAllLines(output));
            assertEquals(failures, Files.readAllLines(avgColorGenerator.failureFile(output)));
        }
    }

    @Test(timeout = 10000)
    public void retriesTransientImageErrorsButStopsAfterThreeAttempts() throws Exception {
        try (Server server = new Server()) {
            byte[] bytes = image();
            AtomicInteger transientAttempts = new AtomicInteger(), persistentAttempts = new AtomicInteger();
            server.http.createContext("/transient", exchange -> {
                if (transientAttempts.incrementAndGet() == 1) {
                    exchange.getResponseHeaders().add("Retry-After", "1");
                    respond(exchange, 429, new byte[] { 0 });
                } else respond(exchange, 200, bytes);
            });
            server.http.createContext("/persistent", exchange -> {
                persistentAttempts.incrementAndGet();
                respond(exchange, 503, new byte[] { 0 });
            });
            server.start();
            Path ids = temporaryFolder.newFile("retry-ids.txt").toPath();
            Path csv = temporaryFolder.newFile("retry.csv").toPath();
            Path output = temporaryFolder.getRoot().toPath().resolve("retry-colors.csv");
            Files.writeString(ids, "1\n2\n");
            Files.writeString(csv, "objectID,primaryImageSmall\n1," + server.base()
                    + "/transient\n2," + server.base() + "/persistent\n");
            assertEquals(1, avgColorGenerator.generate(ids, csv, output));
            assertEquals(2, transientAttempts.get());
            assertEquals(3, persistentAttempts.get());
            assertEquals(List.of("objectID,red,green,blue", "1,40,60,80"), Files.readAllLines(output));
        }
    }

    @Test(timeout = 10000)
    public void flushesHeaderAndSavedRowsBeforeTheRunFinishes() throws Exception {
        try (Server server = new Server(); ExecutorService runner = Executors.newSingleThreadExecutor()) {
            CountDownLatch release = new CountDownLatch(1);
            byte[] bytes = image();
            server.http.createContext("/fast", exchange -> respond(exchange, 200, bytes));
            server.http.createContext("/slow", exchange -> {
                try { release.await(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                respond(exchange, 200, bytes);
            });
            server.start();
            Path ids = temporaryFolder.newFile("flush-ids.txt").toPath();
            Path csv = temporaryFolder.newFile("flush.csv").toPath();
            Path output = temporaryFolder.getRoot().toPath().resolve("flush-colors.csv");
            Files.writeString(ids, "1\n2\n");
            Files.writeString(csv, "objectID,primaryImageSmall\n1," + server.base() + "/fast\n2," + server.base() + "/slow\n");
            Future<Integer> run = runner.submit(() -> avgColorGenerator.generate(ids, csv, output));
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while ((!Files.exists(output) || !Files.readString(output).contains("1,40,60,80")) && System.nanoTime() < deadline) {
                    Thread.sleep(20);
                }
                assertEquals(List.of("objectID,red,green,blue", "1,40,60,80"), Files.readAllLines(output));
                assertFalse(run.isDone());
            } finally { release.countDown(); }
            assertEquals(Integer.valueOf(2), run.get(3, TimeUnit.SECONDS));
        }
    }

    @Test
    public void rejectsInvalidInputWithoutOverwritingSavedColors() throws Exception {
        Path ids = temporaryFolder.newFile("invalid-ids.txt").toPath();
        Path csv = temporaryFolder.newFile("invalid.csv").toPath();
        Path output = temporaryFolder.newFile("existing.csv").toPath();
        Files.writeString(ids, "1\n");
        Files.writeString(csv, "objectID,title\n1,Missing image column\n");
        String saved = "objectID,red,green,blue\n1,10,20,30\n";
        Files.writeString(output, saved);
        try { avgColorGenerator.generate(ids, csv, output); fail("Missing required column should fail"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("primaryImageSmall")); }
        assertEquals(saved, Files.readString(output));
    }
}
