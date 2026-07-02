package config;

import java.util.List;
import java.util.Map;

public class RouteConfig {
    private final String path;
    private final List<String> methods;
    private final String root;
    private final String defaultFile;
    private final Boolean directoryListing;
    private final long clientMaxBodySize;
    private final Map<String, String> cgi;
    private final Map<String, Object> redirect;

    public RouteConfig(String path, List<String> methods,
            String root, String defaultFile, 
            Boolean directoryListing,long clientMaxBodySize, 
            Map<String, String> cgi, Map<String, Object> redirect) {
        this.path = path;
        this.methods = methods;
        this.root = root;
        this.defaultFile = defaultFile;
        this.directoryListing = directoryListing;
        this.clientMaxBodySize = clientMaxBodySize;
        this.cgi = cgi;
        this.redirect = redirect;
    }

    /*------------ Getters ------------*/

    public String getPath() {
        return path;
    }

    public List<String> getMethods() {
        return methods;
    }

    public String getRoot() {
        return root;
    }

    public String getDefaultFile() {
        return defaultFile;
    }

    public Boolean getDirectoryListing() {
        return directoryListing;
    }

    public long getClientMaxBodySize() {
        return clientMaxBodySize;
    }

    public Map<String, String> getCgi() {
        return cgi;
    }

    public Map<String, Object> getRedirect() {
        return redirect;
    }

    /*------------ Helpers ------------*/
    
    public boolean isRedirect() {
        return redirect != null && !redirect.isEmpty();
    }

    public boolean hasCgi() {
        return cgi != null && !cgi.isEmpty();
    }

    public String getCgiInterpreter(String extension) {
        if (cgi == null) {
            return null;
        }
        return cgi.get(extension);
    }
}
