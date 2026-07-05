package server;

import config.ServerConfig;
import config.ConfigLoader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

        Map<Integer, List<ServerConfig>> portToConfigs = new HashMap<>();

        for (ServerConfig serverConfig : configLoader.getServers()) {
            for (Integer port : serverConfig.getPorts()) {
                portToConfigs.computeIfAbsent(port, k -> new ArrayList<>()).add(serverConfig);
            }
        }

        for (Map.Entry<Integer, List<ServerConfig>> entry : portToConfigs.entrySet()) {
            int port = entry.getKey();
            List<ServerConfig> configsForPort = entry.getValue();

            Server server = new Server(configsForPort, port);
            servers.add(server);

            Thread serverThread = new Thread(() -> {
                try {
                    server.start();
                } catch (Exception e) {
                    System.err.println("Server error on port " + port + ": " + e.getMessage());
                }
            });
            
            serverThread.setName("Server-Port-" + port);
            serverThread.start();
            threads.add(serverThread);

            System.out.println("Started server listener on port " + port + " (" + configsForPort.size() + " virtual hosts)");
        }

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