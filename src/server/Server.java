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
import java.util.*;

public class Server {
    private final List<ServerConfig> configs;
    private final Router router;
    private Selector selector;
    private ServerSocketChannel serverSocketChannel;
    private Map<SocketChannel, ClientConnection> connections;
    private volatile boolean running;
    private final int port;

    private static final int BUFFER_SIZE = 8192;

    public Server(List<ServerConfig> configs, int port) {
        this.configs = configs;
        this.port = port;
        this.router = new Router(configs);
        this.connections = new HashMap<>();
    }

    public void start() throws IOException {
        selector = Selector.open();
        serverSocketChannel = ServerSocketChannel.open();
        serverSocketChannel.configureBlocking(false);
        
        String bindHost = configs.get(0).getHost();
        serverSocketChannel.socket().bind(new java.net.InetSocketAddress(bindHost, port));
        serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);

        running = true;

        mainLoop();
    }

    private void mainLoop() {
        while (running) {
            try {
                int readyChannels = selector.select(1000);

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
                        closeConnection(key);
                    }
                }

                cleanupTimedOutConnections();

            } catch (IOException e) {
                System.err.println("Selector error: " + e.getMessage());
            }
        }

        shutdown();
    }

    private void handleAccept(SelectionKey key) throws IOException {
        ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();
        SocketChannel socketChannel = serverChannel.accept();
        socketChannel.configureBlocking(false);

        int timeout = configs.get(0).getRequestTimeout();
        ClientConnection conn = new ClientConnection(socketChannel, timeout);
        connections.put(socketChannel, conn);
        socketChannel.register(selector, SelectionKey.OP_READ, conn);
    }

    private void handleRead(SelectionKey key) throws IOException {
        SocketChannel socketChannel = (SocketChannel) key.channel();
        ClientConnection conn = (ClientConnection) key.attachment();

        ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
        int bytesRead;
        
        try {
            bytesRead = socketChannel.read(buffer);
        } catch (IOException e) {
            closeConnection(key);
            return;
        }

        if (bytesRead == -1) {
            closeConnection(key);
            return;
        }

        if (bytesRead > 0) {
            try {
                conn.appendData(buffer.array(), bytesRead);
                conn.updateLastActivity();

                if (conn.hasCompleteRequest()) {
                    HttpRequest request = conn.parseRequest();
                    conn.clearBuffer();

                    HttpResponse response = router.route(request);
                    conn.setResponse(response);
                    socketChannel.register(selector, SelectionKey.OP_WRITE, conn);
                }
            } catch (Exception e) {
                HttpResponse errorResponse = new HttpResponse();
                if (e.getMessage() != null && e.getMessage().contains("413")) {
                    errorResponse.setStatusCode(413);
                    errorResponse.setBody("Payload Too Large");
                } else {
                    errorResponse.setStatusCode(400);
                    errorResponse.setBody("Bad Request");
                }
                
                conn.setResponse(errorResponse);
                socketChannel.register(selector, SelectionKey.OP_WRITE, conn);
            }
        }
    }

    private void handleWrite(SelectionKey key) throws IOException {
        SocketChannel socketChannel = (SocketChannel) key.channel();
        ClientConnection conn = (ClientConnection) key.attachment();

        if (conn.getWriteBuffer() == null) {
            HttpResponse response = conn.getResponse();
            if (response == null) {
                socketChannel.register(selector, SelectionKey.OP_READ, conn);
                return;
            }
            conn.setWriteBuffer(ByteBuffer.wrap(response.toBytes()));
        }

        try {
            socketChannel.write(conn.getWriteBuffer());
        } catch (IOException e) {
            closeConnection(key);
            return;
        }

        if (!conn.getWriteBuffer().hasRemaining()) {
            conn.clearResponse();
            conn.setWriteBuffer(null);
            socketChannel.register(selector, SelectionKey.OP_READ, conn);
        }
    }

    private void closeConnection(SelectionKey key) {
        try {
            SocketChannel socketChannel = (SocketChannel) key.channel();
            connections.remove(socketChannel);
            socketChannel.close();
            key.cancel();
        } catch (IOException e) {
            // Suppress close exceptions
        }
    }

    private void cleanupTimedOutConnections() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<SocketChannel, ClientConnection>> iterator = connections.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<SocketChannel, ClientConnection> entry = iterator.next();
            ClientConnection conn = entry.getValue();

            if (now - conn.getLastActivity() > conn.getRequestTimeout()) {
                try {
                    entry.getKey().close();
                } catch (IOException e) {
                    // Suppress close exceptions
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
            // Suppress shutdown exceptions
        }
    }

    public boolean isRunning() {
        return running;
    }

    private static class ClientConnection {
        private final SocketChannel channel;
        private final HttpParser parser;
        private long lastActivity;
        private final long requestTimeout;
        private HttpResponse response;
        private ByteBuffer writeBuffer;

        public ClientConnection(SocketChannel channel, long requestTimeout) {
            this.channel = channel;
            this.parser = new HttpParser();
            this.lastActivity = System.currentTimeMillis();
            this.requestTimeout = requestTimeout;
            this.response = null;
            this.writeBuffer = null;
        }

        public void appendData(byte[] data, int length) throws Exception {
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

        public long getRequestTimeout() {
            return requestTimeout;
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

        public ByteBuffer getWriteBuffer() {
            return writeBuffer;
        }

        public void setWriteBuffer(ByteBuffer writeBuffer) {
            this.writeBuffer = writeBuffer;
        }
    }
}