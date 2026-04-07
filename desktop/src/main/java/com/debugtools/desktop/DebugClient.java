package com.debugtools.desktop;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.UUID;

public final class DebugClient implements AutoCloseable {
    private Socket socket;
    private BufferedReader reader;
    private PrintWriter writer;

    public void connect(String host, int port) throws IOException {
        close();
        try {
            socket = new Socket(host, port);
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            writer = new PrintWriter(socket.getOutputStream(), true);
            String hello = reader.readLine();
            if (hello == null) {
                throw new EOFException("Server closed connection during handshake");
            }
        } catch (IOException e) {
            close();
            throw e;
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
}
