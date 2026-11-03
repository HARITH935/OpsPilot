package com.opspilot.gateway;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * A stand-in for one of the three services, on a free local port. It is built on the HTTP server that ships with the
 * JDK (com.sun.net.httpserver), so the gateway tests need no extra library and no real service. It remembers every
 * request the gateway forwarded to it, and answers with whatever the test asked for.
 */
final class FakeService implements AutoCloseable {

    // What the gateway sent: method, path with query string, headers (names in lower case), body
    record Received(String method, String pathAndQuery, Map<String, List<String>> headers, String body) {

        String header(String name) {
            List<String> values = headers.get(name.toLowerCase());
            return values == null ? null : values.getFirst();
        }
    }

    private record Reply(int status, String contentType, String body, Map<String, String> headers, Duration delay) {
    }

    private final HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private volatile Reply reply = new Reply(200, "application/json", "{}", Map.of(), Duration.ZERO);

    private FakeService(HttpServer server) {
        this.server = server;
    }

    static FakeService start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);   // port 0 = any free port
            FakeService fake = new FakeService(server);
            server.createContext("/", fake::handle);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());   // a slow answer doesn't block the others
            server.start();
            return fake;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    String url() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    void answer(int status, String body, Map<String, String> headers) {
        answer(status, "application/json", body, headers);
    }

    void answer(int status, String contentType, String body, Map<String, String> headers) {
        reply = new Reply(status, contentType, body, headers, Duration.ZERO);
    }

    void answerAfter(Duration delay, String body) {
        reply = new Reply(200, "application/json", body, Map.of(), delay);
    }

    // Back to a fresh start between tests: nothing received, answer 200 {}
    void reset() {
        received.clear();
        reply = new Reply(200, "application/json", "{}", Map.of(), Duration.ZERO);
    }

    List<Received> received() {
        return List.copyOf(received);
    }

    Received lastReceived() {
        return received.getLast();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        Map<String, List<String>> headers = new TreeMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(), values));
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        received.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().toString(), headers, body));

        Reply current = reply;
        try {
            Thread.sleep(current.delay());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        byte[] bytes = current.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", current.contentType());
        current.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
        try {
            exchange.sendResponseHeaders(current.status(), bytes.length == 0 ? -1 : bytes.length);   // -1 = no body
            exchange.getResponseBody().write(bytes);
        } catch (IOException e) {
            // The gateway gave up waiting (read timeout) and closed the connection: nobody is left to answer
        } finally {
            exchange.close();
        }
    }
}
