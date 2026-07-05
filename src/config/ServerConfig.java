package config;

import java.util.List;
import java.util.Map;

public class ServerConfig {
    private final String host;
    private final List<Integer> ports;
    private final String serverName;
    private final Boolean defaultServer;
    private final long clientMaxBodySize;
    private final int requestTimeout;
    private final Map<String, String> errorPages;
    private final List<RouteConfig> routes;

    public ServerConfig(String host, List<Integer> ports,
            String serverName, Boolean defaultServer,
            long clientMaxBodySize, int requestTimeout,
            Map<String, String> errorPages, List<RouteConfig> routes) {

        this.host = host;
        this.ports = ports;
        this.serverName = serverName;
        this.defaultServer = defaultServer;
        this.clientMaxBodySize = clientMaxBodySize;
        this.requestTimeout = requestTimeout;
        this.errorPages = errorPages;
        this.routes = routes;
    }

    /*------------ Getters ------------*/

    public String getHost() {
        return host;
    }

    public List<Integer> getPorts() {
        return ports;
    }

    public String getServerName() {
        return serverName;
    }

    public Boolean getDefaultServer() {
        return defaultServer;
    }

    public long getClientMaxBodySize() {
        return clientMaxBodySize;
    }

    public int getRequestTimeout() {
        return requestTimeout;
    }

    public Map<String, String> getErrorPages() {
        return errorPages;
    }

    public List<RouteConfig> getRoutes() {
        return routes;
    }

    /*------------ Helpers ------------*/
    
    @Override
    public String toString() {
        return serverName + " (" + host + ":" + ports + ")";
    }

}
