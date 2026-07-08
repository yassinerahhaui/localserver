package server;

import http.HttpParser;
import http.HttpRequest;
import http.HttpResponse;
import router.Router;
import config.ServerConfig;
import config.RouteConfig;
import cgi.CgiHandler;
import error.ErrorHandler;
import utils.Session;
import utils.SessionManager;
import utils.Metrics;

import java.io.IOException;
import java.io.InputStream;
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
    private final List<ServerSocketChannel> serverSocketChannels;
    private final Map<SocketChannel, ClientConnection> connections;
    private volatile boolean running;

    private static final int BUFFER_SIZE = 8192;

    public Server(List<ServerConfig> configs) {
        this.configs = configs;
        this.router = new Router(configs);
        this.connections = new HashMap<>();
        this.serverSocketChannels = new ArrayList<>();
    }

    public void start() throws IOException {
        selector = Selector.open();

        Set<Integer> uniquePorts = new HashSet<>();
        for (ServerConfig config : configs) {
            uniquePorts.addAll(config.getPorts());
        }

        int successfullyBoundPorts = 0;
        for (int port : uniquePorts) {
            try {
                ServerSocketChannel ssc = ServerSocketChannel.open();
                ssc.configureBlocking(false);
                
                String bindHost = "0.0.0.0";
                for (ServerConfig config : configs) {
                    if (config.getPorts().contains(port)) {
                        bindHost = config.getHost();
                        break;
                    }
                }
                
                ssc.socket().bind(new java.net.InetSocketAddress(bindHost, port));
                ssc.register(selector, SelectionKey.OP_ACCEPT);
                serverSocketChannels.add(ssc);
                successfullyBoundPorts++;
                System.out.println("✓ Server listener bound to " + bindHost + ":" + port);
            } catch (IOException e) {
                System.err.println("✗ Failed to bind server to port " + port + ": " + e.getMessage());
            }
        }

        if (successfullyBoundPorts == 0) {
            throw new IOException("Could not bind to any configured ports");
        }

        running = true;
        mainLoop();
    }

    private void mainLoop() {
        while (running) {
            try {
                // Short select timeout so we can run timeout checks and process CGI tasks frequently
                int readyChannels = selector.select(100);

                // Run non-blocking checks on any pending CGI tasks
                checkCgiTasks();

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

        int localPort = ((java.net.InetSocketAddress) serverChannel.getLocalAddress()).getPort();
        
        // Find default timeout for this port
        int timeout = 30000;
        for (ServerConfig c : configs) {
            if (c.getPorts().contains(localPort)) {
                timeout = c.getRequestTimeout();
                break;
            }
        }

        ClientConnection conn = new ClientConnection(socketChannel, localPort, timeout);
        connections.put(socketChannel, conn);
        socketChannel.register(selector, SelectionKey.OP_READ, conn);

        Metrics.incrementActiveConnections();
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
                conn.updateLastActivity();
                conn.appendData(buffer.array(), bytesRead);

                if (!conn.isRouteChecked() && conn.isHeaderParsed()) {
                    conn.setRouteChecked(true);
                    HttpRequest tempRequest = conn.parseHeadersOnly();
                    
                    ServerConfig serverConfig = resolveVirtualHost(tempRequest, conn.getLocalPort());
                    RouteConfig routeConfig = router.findMatchingRoute(serverConfig, tempRequest.getPath());
                    
                    conn.setActiveServerConfig(serverConfig);
                    conn.setMatchedRoute(routeConfig);
                    
                    // Resolve Session
                    String sessionId = tempRequest.getCookie("session_id");
                    Session session = null;
                    boolean isNew = false;
                    if (sessionId != null) {
                        session = SessionManager.getSession(sessionId);
                    }
                    if (session == null) {
                        session = SessionManager.createSession();
                        isNew = true;
                    }
                    conn.setSession(session);
                    conn.setNewSessionCreated(isNew);
                    
                    // Increment visits count
                    Integer visits = (Integer) session.getAttribute("visits");
                    if (visits == null) visits = 0;
                    session.setAttribute("visits", visits + 1);

                    // Determine keep-alive
                    conn.setKeepAlive(checkKeepAlive(tempRequest));
                }

                long bodyLimit = conn.getMatchedRoute() != null ? 
                        conn.getMatchedRoute().getClientMaxBodySize() : 
                        (conn.getActiveServerConfig() != null ? conn.getActiveServerConfig().getClientMaxBodySize() : 0);

                if (conn.isHeaderParsed() && conn.hasCompleteRequest(bodyLimit)) {
                    HttpRequest request = conn.parseRequest(bodyLimit);
                    conn.resetParser();

                    Metrics.incrementRequests();

                    if (conn.getMatchedRoute() != null && conn.getMatchedRoute().hasCgi()) {
                        try {
                            String host = conn.getActiveServerConfig().getHost();
                            Process proc = CgiHandler.startCgiProcess(request, conn.getMatchedRoute(), conn.getMatchedRoute().getRoot(), host, conn.getLocalPort());
                            conn.setCgiProcess(proc);
                            conn.setCgiStdout(proc.getInputStream());
                            conn.setCgiOutput(new java.io.ByteArrayOutputStream());
                            conn.setCgiStartTime(System.currentTimeMillis());
                            conn.setWaitingForCgi(true);
                            
                            // Stop reading until CGI completes
                            key.interestOps(0);
                        } catch (Exception e) {
                            int code = 500;
                            String msg = "Internal Server Error";
                            if (e.getMessage() != null) {
                                if (e.getMessage().contains("403")) {
                                    code = 403;
                                    msg = "Forbidden: CGI not allowed";
                                } else if (e.getMessage().contains("404")) {
                                    code = 404;
                                    msg = "Not Found: CGI script not found";
                                }
                            }
                            http.HttpRequest errReq = null;
                            try {
                                errReq = conn.parseHeadersOnly();
                            } catch (Exception ex) {
                                // Suppress
                            }
                            HttpResponse errorResponse = ErrorHandler.handleError(conn.getActiveServerConfig(), code, msg, errReq);
                            conn.setResponse(errorResponse);
                            socketChannel.register(selector, SelectionKey.OP_WRITE, conn);
                        }
                    } else {
                        HttpResponse response = router.route(request, conn.getActiveServerConfig(), conn.getSession());
                        conn.setResponse(response);
                        socketChannel.register(selector, SelectionKey.OP_WRITE, conn);
                    }
                }
            } catch (Exception e) {
                int statusCode = 400;
                String statusMsg = "Bad Request";
                if (e.getMessage() != null && e.getMessage().contains("413")) {
                    statusCode = 413;
                    statusMsg = "Payload Too Large: File size exceeds the maximum configured limit";
                }
                
                http.HttpRequest errReq = null;
                try {
                    errReq = conn.parseHeadersOnly();
                } catch (Exception ex) {
                    // Suppress
                }
                
                HttpResponse errorResponse = ErrorHandler.handleError(conn.getActiveServerConfig(), statusCode, statusMsg, errReq);
                conn.setResponse(errorResponse);
                try {
                    socketChannel.register(selector, SelectionKey.OP_WRITE, conn);
                } catch (Exception ex) {
                    closeConnection(key);
                }
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

            if (response.getFile() != null) {
                java.io.File file = response.getFile();
                java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r");
                conn.setFileChannel(raf.getChannel());
                conn.setFilePosition(0);
                conn.setFileLength(file.length());
            }
        }

        if (conn.getWriteBuffer().hasRemaining()) {
            try {
                socketChannel.write(conn.getWriteBuffer());
            } catch (IOException e) {
                conn.closeFileChannel();
                closeConnection(key);
                return;
            }
        }

        if (!conn.getWriteBuffer().hasRemaining()) {
            java.nio.channels.FileChannel fileChannel = conn.getFileChannel();
            if (fileChannel != null) {
                long pos = conn.getFilePosition();
                long count = conn.getFileLength() - pos;
                if (count > 0) {
                    long written = 0;
                    try {
                        long toTransfer = Math.min(count, 8 * 1024 * 1024L);
                        written = fileChannel.transferTo(pos, toTransfer, socketChannel);
                    } catch (IOException e) {
                        conn.closeFileChannel();
                        closeConnection(key);
                        return;
                    }
                    if (written > 0) {
                        conn.setFilePosition(pos + written);
                        conn.updateLastActivity();
                    }
                    if (conn.getFilePosition() < conn.getFileLength()) {
                        return;
                    }
                }
                conn.closeFileChannel();
            }

            boolean keepAlive = conn.isKeepAlive();
            HttpResponse resp = conn.getResponse();
            if (resp != null) {
                String respConn = resp.getHeader("connection");
                if (respConn != null && respConn.equalsIgnoreCase("close")) {
                    keepAlive = false;
                }
            }
            conn.clearResponse();
            conn.setWriteBuffer(null);
            if (keepAlive) {
                socketChannel.register(selector, SelectionKey.OP_READ, conn);
            } else {
                closeConnection(key);
            }
        }
    }

    private void checkCgiTasks() {
        long now = System.currentTimeMillis();
        long cgiTimeout = 5000; // 5 seconds CGI execution timeout

        for (Map.Entry<SocketChannel, ClientConnection> entry : connections.entrySet()) {
            ClientConnection conn = entry.getValue();

            if (conn.isWaitingForCgi()) {
                Process process = conn.getCgiProcess();
                try {
                    InputStream stdout = conn.getCgiStdout();
                    int avail = stdout.available();
                    if (avail > 0) {
                        byte[] buffer = new byte[avail];
                        int read = stdout.read(buffer);
                        if (read > 0) {
                            conn.getCgiOutput().write(buffer, 0, read);
                        }
                    }

                    if (process.isAlive()) {
                        if (now - conn.getCgiStartTime() > cgiTimeout) {
                            process.destroyForcibly();
                            stdout.close();
                            
                            HttpResponse errorResponse = ErrorHandler.handleError(
                                    conn.getActiveServerConfig(), 504, "Gateway Timeout: CGI script took too long", conn.getRequest());
                            conn.setResponse(errorResponse);
                            conn.setWaitingForCgi(false);
                            
                            conn.getChannel().register(selector, SelectionKey.OP_WRITE, conn);
                        }
                    } else {
                        // Read any leftover bytes
                        int read;
                        byte[] buffer = new byte[8192];
                        while (stdout.available() > 0 && (read = stdout.read(buffer)) != -1) {
                            conn.getCgiOutput().write(buffer, 0, read);
                        }
                        
                        stdout.close();
                        int exitCode = process.exitValue();
                        
                        HttpResponse response;
                        if (exitCode != 0) {
                            response = ErrorHandler.handleError(
                                    conn.getActiveServerConfig(), 500, "CGI Script Error (exit code: " + exitCode + ")", conn.getRequest());
                        } else {
                            response = new HttpResponse();
                            CgiHandler.parseCgiResponse(response, conn.getCgiOutput().toByteArray());
                        }

                        conn.setResponse(response);
                        conn.setWaitingForCgi(false);
                        
                        conn.getChannel().register(selector, SelectionKey.OP_WRITE, conn);
                    }
                } catch (Exception e) {
                    process.destroyForcibly();
                    try {
                        conn.getCgiStdout().close();
                    } catch (IOException io) {
                        // Suppress
                    }
                    HttpResponse errorResponse = ErrorHandler.handleError(
                            conn.getActiveServerConfig(), 500, "Internal Server Error during CGI execution", conn.getRequest());
                    conn.setResponse(errorResponse);
                    conn.setWaitingForCgi(false);
                    try {
                        conn.getChannel().register(selector, SelectionKey.OP_WRITE, conn);
                    } catch (Exception ex) {
                        // Suppress
                    }
                }
            }
        }
    }

    private void closeConnection(SelectionKey key) {
        try {
            SocketChannel socketChannel = (SocketChannel) key.channel();
            closeSocketChannel(socketChannel);
            key.cancel();
        } catch (Exception e) {
            // Suppress close exceptions
        }
    }

    private void closeSocketChannel(SocketChannel socketChannel) {
        if (socketChannel != null) {
            ClientConnection conn = connections.remove(socketChannel);
            if (conn != null) {
                if (conn.isWaitingForCgi() && conn.getCgiProcess() != null) {
                    conn.getCgiProcess().destroyForcibly();
                }
                conn.closeFileChannel();
                Metrics.decrementActiveConnections();
            }
            try {
                socketChannel.close();
            } catch (IOException e) {
                // Suppress
            }
        }
    }

    private void cleanupTimedOutConnections() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<SocketChannel, ClientConnection>> iterator = connections.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<SocketChannel, ClientConnection> entry = iterator.next();
            ClientConnection conn = entry.getValue();

            if (!conn.isWaitingForCgi() && (now - conn.getLastActivity() > conn.getRequestTimeout())) {
                conn.closeFileChannel();
                try {
                    entry.getKey().close();
                } catch (IOException e) {
                    // Suppress
                }
                iterator.remove();
                Metrics.decrementActiveConnections();
            }
        }
    }

    public void shutdown() {
        running = false;
        try {
            for (SocketChannel channel : new ArrayList<>(connections.keySet())) {
                closeSocketChannel(channel);
            }
            for (ServerSocketChannel ssc : serverSocketChannels) {
                ssc.close();
            }
            if (selector != null) {
                selector.close();
            }
        } catch (IOException e) {
            // Suppress shutdown exceptions
        }
    }

    public boolean isRunning() {
        return running;
    }

    private boolean checkKeepAlive(HttpRequest request) {
        String connHeader = request.getHeader("connection");
        if (connHeader != null) {
            return connHeader.equalsIgnoreCase("keep-alive");
        }
        return "HTTP/1.1".equalsIgnoreCase(request.getHttpVersion());
    }

    private ServerConfig resolveVirtualHost(HttpRequest request, int localPort) {
        String hostHeader = request.getHeader("host");
        String requestedHost = null;
        if (hostHeader != null) {
            requestedHost = hostHeader.split(":")[0];
        }

        if (requestedHost != null) {
            for (ServerConfig config : configs) {
                if (config.getPorts().contains(localPort)) {
                    if (config.getServerName() != null && config.getServerName().equals(requestedHost)) {
                        return config;
                    }
                }
            }
        }

        for (ServerConfig config : configs) {
            if (config.getPorts().contains(localPort)) {
                if (config.getDefaultServer() != null && config.getDefaultServer()) {
                    return config;
                }
            }
        }

        for (ServerConfig config : configs) {
            if (config.getPorts().contains(localPort)) {
                return config;
            }
        }

        return configs.isEmpty() ? null : configs.get(0);
    }

    private static class ClientConnection {
        private final SocketChannel channel;
        private final HttpParser parser;
        private final int localPort;
        private long lastActivity;
        private final long requestTimeout;
        private HttpResponse response;
        private ByteBuffer writeBuffer;
        private boolean routeChecked = false;
        private ServerConfig activeServerConfig = null;
        private RouteConfig matchedRoute = null;
        private Session session = null;
        private boolean newSessionCreated = false;
        private boolean keepAlive = false;

        private boolean isWaitingForCgi = false;
        private Process cgiProcess = null;
        private long cgiStartTime = 0;
        private InputStream cgiStdout = null;
        private java.io.ByteArrayOutputStream cgiOutput = null;

        private java.nio.channels.FileChannel fileChannel = null;
        private long filePosition = 0;
        private long fileLength = 0;

        public ClientConnection(SocketChannel channel, int localPort, long requestTimeout) {
            this.channel = channel;
            this.localPort = localPort;
            this.parser = new HttpParser();
            this.lastActivity = System.currentTimeMillis();
            this.requestTimeout = requestTimeout;
            this.response = null;
            this.writeBuffer = null;
        }

        public SocketChannel getChannel() {
            return channel;
        }

        public int getLocalPort() {
            return localPort;
        }

        public boolean isRouteChecked() {
            return routeChecked;
        }

        public void setRouteChecked(boolean routeChecked) {
            this.routeChecked = routeChecked;
        }

        public boolean isHeaderParsed() {
            return parser.isHeaderParsed();
        }

        public HttpRequest parseHeadersOnly() throws Exception {
            return parser.parseHeadersOnly();
        }

        public void appendData(byte[] data, int length) throws Exception {
            parser.appendData(data, length);
        }

        public boolean hasCompleteRequest(long limit) throws Exception {
            return parser.hasCompleteRequest(limit);
        }

        public HttpRequest parseRequest(long limit) throws Exception {
            return parser.parseRequest(limit);
        }

        public void resetParser() {
            parser.reset();
            this.routeChecked = false;
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
            if (response != null) {
                if (newSessionCreated && session != null) {
                    response.addCookie("session_id", session.getId());
                }
                Metrics.recordResponseStatus(response.getStatusCode());
            }
        }

        public HttpResponse getResponse() {
            return response;
        }

        public HttpRequest getRequest() {
            try {
                return parser.parseRequest(0);
            } catch (Exception e) {
                return null;
            }
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

        public ServerConfig getActiveServerConfig() {
            return activeServerConfig;
        }

        public void setActiveServerConfig(ServerConfig activeServerConfig) {
            this.activeServerConfig = activeServerConfig;
        }

        public RouteConfig getMatchedRoute() {
            return matchedRoute;
        }

        public void setMatchedRoute(RouteConfig matchedRoute) {
            this.matchedRoute = matchedRoute;
        }

        public Session getSession() {
            return session;
        }

        public void setSession(Session session) {
            this.session = session;
        }

        public void setNewSessionCreated(boolean newSessionCreated) {
            this.newSessionCreated = newSessionCreated;
        }

        public boolean isKeepAlive() {
            return keepAlive;
        }

        public void setKeepAlive(boolean keepAlive) {
            this.keepAlive = keepAlive;
        }

        public boolean isWaitingForCgi() {
            return isWaitingForCgi;
        }

        public void setWaitingForCgi(boolean waitingForCgi) {
            isWaitingForCgi = waitingForCgi;
        }

        public Process getCgiProcess() {
            return cgiProcess;
        }

        public void setCgiProcess(Process cgiProcess) {
            this.cgiProcess = cgiProcess;
        }

        public long getCgiStartTime() {
            return cgiStartTime;
        }

        public void setCgiStartTime(long cgiStartTime) {
            this.cgiStartTime = cgiStartTime;
        }

        public InputStream getCgiStdout() {
            return cgiStdout;
        }

        public void setCgiStdout(InputStream cgiStdout) {
            this.cgiStdout = cgiStdout;
        }

        public java.io.ByteArrayOutputStream getCgiOutput() {
            return cgiOutput;
        }

        public void setCgiOutput(java.io.ByteArrayOutputStream cgiOutput) {
            this.cgiOutput = cgiOutput;
        }

        public java.nio.channels.FileChannel getFileChannel() {
            return fileChannel;
        }

        public void setFileChannel(java.nio.channels.FileChannel fileChannel) {
            this.fileChannel = fileChannel;
        }

        public long getFilePosition() {
            return filePosition;
        }

        public void setFilePosition(long filePosition) {
            this.filePosition = filePosition;
        }

        public long getFileLength() {
            return fileLength;
        }

        public void setFileLength(long fileLength) {
            this.fileLength = fileLength;
        }

        public void closeFileChannel() {
            if (fileChannel != null) {
                try {
                    fileChannel.close();
                } catch (IOException e) {
                    // Suppress
                }
                fileChannel = null;
            }
        }
    }
}