package config;

import java.io.IOException;
import java.util.*;

public class ConfigLoader {
    private List<ServerConfig> servers;

    public ConfigLoader() {
        this.servers = new ArrayList<>();
    }

    public static ConfigLoader fromFile(String filePath) throws IOException {
        ConfigLoader loader = new ConfigLoader();
        loader.load(filePath);
        try {
            loader.validate();
        } catch (Exception e) {
            throw new IOException("Configuration validation failed: " + e.getMessage(), e);
        }
        return loader;
    }

    public void load(String filePath) throws IOException {
        SimpleJsonParser parser = SimpleJsonParser.fromFile(filePath);
        Map<String, Object> root = (Map<String, Object>) parser.parse();

        List<Object> serversList = (List<Object>) root.get("servers");
        if (serversList != null) {
            for (Object serverObj : serversList) {
                Map<String, Object> serverMap = (Map<String, Object>) serverObj;
                ServerConfig server = parseServerConfig(serverMap);
                servers.add(server);
            }
        }
    }

    private ServerConfig parseServerConfig(Map<String, Object> serverMap) {
        String host = (String) serverMap.get("host");
        List<Integer> ports = parsePortList((List<Object>) serverMap.get("ports"));
        String serverName = (String) serverMap.get("server_name");
        Boolean defaultServer = (Boolean) serverMap.get("default_server");
        
        long clientMaxBodySize = SimpleJsonParser.parseSize((String) serverMap.get("client_max_body_size"));
        Number timeoutNum = (Number) serverMap.get("request_timeout_ms");
        int requestTimeout = timeoutNum != null ? timeoutNum.intValue() : 30000;

        Map<String, String> errorPages = parseErrorPages((Map<String, Object>) serverMap.get("error_pages"));
        List<RouteConfig> routes = parseRoutes((List<Object>) serverMap.get("routes"), clientMaxBodySize);

        return new ServerConfig(host, ports, serverName, defaultServer != null && defaultServer,
                clientMaxBodySize, requestTimeout, errorPages, routes);
    }

    private List<Integer> parsePortList(List<Object> portsList) {
        List<Integer> ports = new ArrayList<>();
        if (portsList != null) {
            for (Object port : portsList) {
                if (port instanceof Number) {
                    ports.add(((Number) port).intValue());
                }
            }
        }
        return ports;
    }

    private Map<String, String> parseErrorPages(Map<String, Object> errorPagesMap) {
        Map<String, String> errorPages = new HashMap<>();
        if (errorPagesMap != null) {
            for (Map.Entry<String, Object> entry : errorPagesMap.entrySet()) {
                errorPages.put(entry.getKey(), (String) entry.getValue());
            }
        }
        return errorPages;
    }

    private List<RouteConfig> parseRoutes(List<Object> routesList, long defaultClientMaxBodySize) {
        List<RouteConfig> routes = new ArrayList<>();
        if (routesList != null) {
            for (Object routeObj : routesList) {
                Map<String, Object> routeMap = (Map<String, Object>) routeObj;
                RouteConfig route = parseRouteConfig(routeMap, defaultClientMaxBodySize);
                routes.add(route);
            }
        }
        return routes;
    }

    private RouteConfig parseRouteConfig(Map<String, Object> routeMap, long defaultClientMaxBodySize) {
        String path = (String) routeMap.get("path");
        List<String> methods = parseMethods((List<Object>) routeMap.get("methods"));
        String root = (String) routeMap.get("root");
        String defaultFile = (String) routeMap.get("default_file");
        Boolean directoryListing = (Boolean) routeMap.get("directory_listing");
        
        Object sizeObj = routeMap.get("client_max_body_size");
        long clientMaxBodySize = defaultClientMaxBodySize;
        if (sizeObj != null) {
            if (sizeObj instanceof String) {
                clientMaxBodySize = SimpleJsonParser.parseSize((String) sizeObj);
            } else if (sizeObj instanceof Number) {
                clientMaxBodySize = ((Number) sizeObj).longValue();
            }
        }

        Map<String, String> cgi = parseCgi((Map<String, Object>) routeMap.get("cgi"));
        Map<String, Object> redirect = (Map<String, Object>) routeMap.get("redirect");

        return new RouteConfig(path, methods, root, defaultFile,
                directoryListing != null && directoryListing, clientMaxBodySize, cgi, redirect);
    }

    private List<String> parseMethods(List<Object> methodsList) {
        List<String> methods = new ArrayList<>();
        if (methodsList != null) {
            for (Object method : methodsList) {
                methods.add((String) method);
            }
        }
        return methods;
    }

    private Map<String, String> parseCgi(Map<String, Object> cgiMap) {
        Map<String, String> cgi = new HashMap<>();
        if (cgiMap != null) {
            for (Map.Entry<String, Object> entry : cgiMap.entrySet()) {
                cgi.put(entry.getKey(), (String) entry.getValue());
            }
        }
        return cgi;
    }

    public List<ServerConfig> getServers() {
        return servers;
    }

    public ServerConfig getDefaultServer() {
        for (ServerConfig server : servers) {
            if (server.getDefaultServer()) {
                return server;
            }
        }
        return servers.isEmpty() ? null : servers.get(0);
    }

    public void validate() throws Exception {
        if (servers.isEmpty()) {
            throw new Exception("No servers configured");
        }

        Set<String> serverKeys = new HashSet<>();
        Map<Integer, Boolean> portHasDefault = new HashMap<>();

        for (ServerConfig server : servers) {
            if (server.getHost() == null || server.getHost().trim().isEmpty()) {
                throw new Exception("Server host cannot be empty");
            }
            if (server.getPorts() == null || server.getPorts().isEmpty()) {
                throw new Exception("Server ports list cannot be empty");
            }

            Set<Integer> uniquePortsInServer = new HashSet<>();
            for (Integer port : server.getPorts()) {
                if (port <= 0 || port > 65535) {
                    throw new Exception("Invalid port number: " + port);
                }
                if (!uniquePortsInServer.add(port)) {
                    throw new Exception("Duplicate port " + port + " configured for server " + server.getServerName());
                }

                String hostPortNameKey = server.getHost() + ":" + port + ":" + (server.getServerName() != null ? server.getServerName().trim() : "");
                if (!serverKeys.add(hostPortNameKey)) {
                    throw new Exception("Conflict: Server name '" + server.getServerName() + "' is configured multiple times on port " + port);
                }

                if (server.getDefaultServer()) {
                    if (portHasDefault.containsKey(port)) {
                        throw new Exception("Conflict: Multiple default servers configured for port " + port);
                    }
                    portHasDefault.put(port, true);
                }
            }

            if (server.getRoutes() != null) {
                Set<String> routePaths = new HashSet<>();
                for (RouteConfig route : server.getRoutes()) {
                    if (route.getPath() == null || route.getPath().trim().isEmpty()) {
                        throw new Exception("Route path cannot be empty");
                    }
                    if (!routePaths.add(route.getPath())) {
                        throw new Exception("Duplicate route path '" + route.getPath() + "' in server " + server.getServerName());
                    }
                    if (route.getMethods() == null || route.getMethods().isEmpty()) {
                        throw new Exception("Route " + route.getPath() + " must allow at least one method");
                    }
                    if (route.hasCgi()) {
                        for (Map.Entry<String, String> cgiEntry : route.getCgi().entrySet()) {
                            if (cgiEntry.getKey() == null || cgiEntry.getKey().trim().isEmpty()) {
                                                    throw new Exception("CGI extension cannot be empty");
                            }
                            if (cgiEntry.getValue() == null || cgiEntry.getValue().trim().isEmpty()) {
                                                    throw new Exception("CGI interpreter cannot be empty");
                            }
                        }
                    }
                }
            }
        }
    }
}
