package server;

import config.ServerConfig;
import config.ConfigLoader;
import java.util.List;

public class ServerManager {
    private Server server;
    private ConfigLoader configLoader;

    public ServerManager(String configPath) throws Exception {
        this.configLoader = ConfigLoader.fromFile(configPath);
        this.server = new Server(configLoader.getServers());
    }

    public void startAll() throws Exception {
        if (configLoader.getServers().isEmpty()) {
            throw new Exception("No servers configured");
        }
        
        System.out.println("Starting single-threaded event-driven HTTP server...");
        server.start();
    }

    public void shutdownAll() {
        if (server != null) {
            server.shutdown();
        }
    }

    public Server getServer() {
        return server;
    }
}