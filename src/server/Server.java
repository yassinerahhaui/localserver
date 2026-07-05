package server;

import http.HttpParser;
import http.HttpRequest;
import http.HttpResponse;
import router.Router;
import config.ServerConfig;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class Server {
    private final ServerConfig config;
    private final Router router;
    private Selector selector;
    private ServerSocketChannel serverSocketChannel;
    private Map<SocketChannel, ClientConnection> connections;
    private volatile boolean running;
    private final int port;

    private static final int BUFFER_SIZE = 8192;

    public Server(ServerConfig config, int port) {
        this.config = config;
        this.port = port;
        this.router = new Router(config);
        this.connections = new HashMap<>();
    }

    public void start() throws IOException {
        selector = Selector.open();
        serverSocketChannel = ServerSocketChannel.open();
        serverSocketChannel.configureBlocking(false);
        serverSocketChannel.socket().bind(new java.net.InetSocketAddress(config.getHost(), port));
        serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);

        running = true;
        System.out.println("Server started on " + config.getHost() + ":" + port);

        mainLoop();
    }

    private void mainLoop() {
        while (running) {
            try {
                // Select ready channels
                int readyChannels = selector.select(1000); // 1 second timeout

                if (readyChannels == 0) {
                    cleanupTimedOutConnections();
                    continue;
                }

                Set<SelectionKey> selectedKeys = selector.selectedKeys();
                Iterator<SelectionKey> iterator = selectedKeys.iterator();

                while (iterator.hasNext()) {
                    SelectionKey key = iterator.next();
                    iterator.remove();

                    if (!key.isValid()) {
                        continue;
                    }

                    try {
                        if (key.isAcceptable()) {
                            handleAccept(key);
                        } else if (key.isReadable()) {
                            handleRead(key);
                        } else if (key.isWritable()) {
                            handleWrite(key);
                        }
                    } catch (Exception e) {
                        System.err.println("Error handling connection: " + e.getMessage());
                        closeConnection(key);
                    }
                }

                cleanupTimedOutConnections();

            } catch (IOException e) {
                System.err.println("Selector error: " + e.getMessage());
                e.printStackTrace();
            }
        }

        shutdown();
    }

    private void handleAccept(SelectionKey key) throws IOException {
        ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();
        SocketChannel socketChannel = serverChannel.accept();
        socketChannel.configureBlocking(false);

        ClientConnection conn = new ClientConnection(socketChannel, config.getRequestTimeout());
        connections.put(socketChannel, conn);
        socketChannel.register(selector, SelectionKey.OP_READ, conn);

        System.out.println("New connection from " + socketChannel.getRemoteAddress());
    }

    private void handleRead(SelectionKey key) throws IOException {
        SocketChannel socketChannel = (SocketChannel) key.channel();
        ClientConnection conn = (ClientConnection) key.attachment();

        ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
        int bytesRead = socketChannel.read(buffer);

        if (bytesRead == -1) {
            // Client closed connection
            closeConnection(key);
            return;
        }

        if (bytesRead > 0) {
            conn.appendData(buffer.array(), bytesRead);
            conn.updateLastActivity();

            // Check if we have a complete request
            if (conn.hasCompleteRequest()) {
                try {
                    HttpRequest request = conn.parseRequest();
                    conn.clearBuffer();

                    // Route request
                    HttpResponse response = router.route(request);

                    // Send response
                    conn.setResponse(response);
                    socketChannel.register(selector, SelectionKey.OP_WRITE, conn);

                } catch (Exception e) {
                    System.err.println("Error parsing request: " + e.getMessage());
                    HttpResponse errorResponse = new HttpResponse();
                    errorResponse.setStatusCode(400);
                    errorResponse.setBody("Bad Request");
                    conn.setResponse(errorResponse);
                    socketChannel.register(selector, SelectionKey.OP_WRITE, conn);
                }
            }
        }
    }

    private void handleWrite(SelectionKey key) throws IOException {
        SocketChannel socketChannel = (SocketChannel) key.channel();
        ClientConnection conn = (ClientConnection) key.attachment();

        HttpResponse response = conn.getResponse();
        if (response == null) {
            socketChannel.register(selector, SelectionKey.OP_READ, conn);
            return;
        }

        byte[] responseBytes = response.toBytes();
        ByteBuffer buffer = ByteBuffer.wrap(responseBytes);

        int bytesWritten = socketChannel.write(buffer);

        if (!buffer.hasRemaining()) {
            // Response fully sent
            conn.clearResponse();
            socketChannel.register(selector, SelectionKey.OP_READ, conn);
        }
    }

    private void closeConnection(SelectionKey key) {
        try {
            SocketChannel socketChannel = (SocketChannel) key.channel();
            ClientConnection conn = connections.remove(socketChannel);

            socketChannel.close();
            key.cancel();
            
            try {
                System.out.println("Connection closed: " + socketChannel.getRemoteAddress());
            } catch (Exception e) {
                System.out.println("Connection closed");
            }
        } catch (IOException e) {
            System.err.println("Error closing connection: " + e.getMessage());
        }
    }

    private void cleanupTimedOutConnections() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<SocketChannel, ClientConnection>> iterator = connections.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<SocketChannel, ClientConnection> entry = iterator.next();
            ClientConnection conn = entry.getValue();

            if (now - conn.getLastActivity() > config.getRequestTimeout()) {
                System.out.println("Connection timeout: " + entry.getKey());
                try {
                    entry.getKey().close();
                } catch (IOException e) {
                    // Ignore
                }
                iterator.remove();
            }
        }
    }

    public void shutdown() {
        running = false;
        try {
            for (SocketChannel channel : connections.keySet()) {
                channel.close();
            }
            serverSocketChannel.close();
            selector.close();
        } catch (IOException e) {
            System.err.println("Error shutting down: " + e.getMessage());
        }
        System.out.println("Server stopped");
    }

    public boolean isRunning() {
        return running;
    }

    /**
     * Inner class to track per-connection state
     */
    private static class ClientConnection {
        private final SocketChannel channel;
        private final HttpParser parser;
        private long lastActivity;
        private final long requestTimeout;
        private HttpResponse response;
        private boolean requestComplete;

        public ClientConnection(SocketChannel channel, int requestTimeout) {
            this.channel = channel;
            this.parser = new HttpParser();
            this.lastActivity = System.currentTimeMillis();
            this.requestTimeout = requestTimeout;
            this.response = null;
            this.requestComplete = false;
        }

        public void appendData(byte[] data, int length) {
            parser.appendData(data, length);
        }

        public boolean hasCompleteRequest() {
            return parser.hasCompleteRequest();
        }

        public HttpRequest parseRequest() throws Exception {
            return parser.parseRequest();
        }

        public void clearBuffer() {
            parser.reset();
        }

        public void updateLastActivity() {
            this.lastActivity = System.currentTimeMillis();
        }

        public long getLastActivity() {
            return lastActivity;
        }

        public void setResponse(HttpResponse response) {
            this.response = response;
        }

        public HttpResponse getResponse() {
            return response;
        }

        public void clearResponse() {
            this.response = null;
        }
    }
}
