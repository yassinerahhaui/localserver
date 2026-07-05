import server.ServerManager;

public class Main {
    public static void main(String[] args) {
        try {
            String configPath = "config.json";
            if (args.length > 0) {
                configPath = args[0];
            }

            System.out.println("=== Custom HTTP Server ===");
            System.out.println("Loading configuration from: " + configPath);

            ServerManager manager = new ServerManager(configPath);
            
            // Add shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("\nShutting down servers...");
                manager.shutdownAll();
            }));

            manager.startAll();

        } catch (Exception e) {
            System.err.println("Fatal error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
