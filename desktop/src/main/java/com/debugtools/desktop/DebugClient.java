package com.debugtools.desktop;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;

public final class DebugClient implements AutoCloseable {
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 1200;
    private Socket socket;
    private BufferedReader reader;
    private PrintWriter writer;

    public void connect(String host, int port) throws IOException {
        close();
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), DEFAULT_CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(DEFAULT_CONNECT_TIMEOUT_MS);
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            writer = new PrintWriter(socket.getOutputStream(), true);
            String hello = reader.readLine();
            if (hello == null) {
                throw new EOFException("Server closed connection during handshake");
            }
            parseHandshake(host, port, hello);
        } catch (IOException e) {
            close();
            throw e;
        }
    }

    public static ProbeResult probe(String host, int port, int timeoutMs) throws IOException {
        try (Socket probeSocket = new Socket()) {
            probeSocket.connect(new InetSocketAddress(host, port), timeoutMs);
            probeSocket.setSoTimeout(timeoutMs);
            try (
                BufferedReader probeReader = new BufferedReader(new InputStreamReader(probeSocket.getInputStream()));
                PrintWriter probeWriter = new PrintWriter(probeSocket.getOutputStream(), true)
            ) {
                String hello = probeReader.readLine();
                if (hello == null) {
                    throw new EOFException("Server closed connection during handshake");
                }
                return parseHandshake(host, port, hello);
            }
        }
    }

    public String send(String type, String payloadJson) throws IOException {
        String id = UUID.randomUUID().toString();
        String request = "{\"id\":\"" + id + "\",\"type\":\"" + type + "\"" + payloadJson + "}";
        writer.println(request);
        String response = reader.readLine();
        if (response == null) {
            throw new EOFException("Server closed connection while waiting for response: " + type);
        }
        return response;
    }

    @Override
    public void close() throws IOException {
        if (reader != null) reader.close();
        if (writer != null) writer.close();
        if (socket != null) socket.close();
        reader = null;
        writer = null;
        socket = null;
    }

    private static ProbeResult parseHandshake(String host, int port, String helloLine) throws IOException {
        JsonNode hello = Jsons.parse(helloLine);
        if (hello == null) {
            throw new IOException("Invalid handshake JSON");
        }
        if (!"hello".equals(Jsons.text(hello, "type", "")) || !hello.path("success").asBoolean(false)) {
            throw new IOException("Unexpected handshake message");
        }
        JsonNode payload = Jsons.payload(hello);
        return new ProbeResult(
            host,
            port,
            Jsons.text(payload, "app", "(unknown app)"),
            Jsons.text(payload, "host", host),
            Jsons.integer(payload, "port", port)
        );
    }

    public static final class ProbeResult {
        public final String host;
        public final int port;
        public final String appPackage;
        public final String serverHost;
        public final int serverPort;

        private ProbeResult(String host, int port, String appPackage, String serverHost, int serverPort) {
            this.host = host;
            this.port = port;
            this.appPackage = appPackage;
            this.serverHost = serverHost;
            this.serverPort = serverPort;
        }
    }
}
