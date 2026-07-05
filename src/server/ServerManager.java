package server;

import config.ServerConfig;
import config.ConfigLoader;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages multiple server instances
 */
public class ServerManager {
    private List<Server> servers;
    private List<Thread> threads;
    private ConfigLoader configLoader;

    public ServerManager(String configPath) throws Exception {
        this.servers = new ArrayList<>();
        this.threads = new ArrayList<>();
        this.configLoader = ConfigLoader.fromFile(configPath);
    }

    public void startAll() throws Exception {
        if (configLoader.getServers().isEmpty()) {
            throw new Exception("No servers configured");
        }

        for (ServerConfig serverConfig : configLoader.getServers()) {
            for (Integer port : serverConfig.getPorts()) {
                Server server = new Server(serverConfig, port);
                servers.add(server);

                Thread serverThread = new Thread(() -> {
                    try {
                        server.start();
                    } catch (Exception e) {
                        System.err.println("Server error on port " + port + ": " + e.getMessage());
                        e.printStackTrace();
                    }
                });
                serverThread.setName("Server-" + serverConfig.getServerName() + "-" + port);
                serverThread.start();
                threads.add(serverThread);

                System.out.println("Started " + serverConfig.getServerName() + " on port " + port);
            }
        }

        // Wait for all threads
        for (Thread thread : threads) {
            thread.join();
        }
    }

    public void shutdownAll() {
        for (Server server : servers) {
            server.shutdown();
        }
    }

    public List<Server> getServers() {
        return servers;
    }
}
