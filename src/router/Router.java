package router;

import config.RouteConfig;
import config.ServerConfig;
import http.HttpRequest;
import http.HttpResponse;
import handlers.StaticFileHandler;
import cgi.CgiHandler;
import error.ErrorHandler;

import java.util.List;
import java.util.Map;

public class Router {
    private final List<ServerConfig> serverConfigs;

    public Router(List<ServerConfig> serverConfigs) {
        this.serverConfigs = serverConfigs;
    }

    public HttpResponse route(HttpRequest request) {
        ServerConfig activeServerConfig = resolveVirtualHost(request);
        
        String path = request.getPath();
        String method = request.getMethod();

        RouteConfig matchedRoute = findMatchingRoute(activeServerConfig, path);

        if (matchedRoute == null) {
            return ErrorHandler.handleError(activeServerConfig, 404, "Route not found: " + path);
        }

        if (!matchedRoute.getMethods().contains(method)) {
            return ErrorHandler.handleError(activeServerConfig, 405, "Method " + method + " not allowed for " + path);
        }

        if (matchedRoute.isRedirect()) {
            return handleRedirect(matchedRoute);
        }

        if (matchedRoute.hasCgi()) {
            return CgiHandler.handleCgiRequest(request, matchedRoute, matchedRoute.getRoot());
        }

        long activeMaxBodySize = matchedRoute.getClientMaxBodySize() > 0 
                                 ? matchedRoute.getClientMaxBodySize() 
                                 : activeServerConfig.getClientMaxBodySize();

        if (activeMaxBodySize > 0 && request.getBody().length > activeMaxBodySize) {
            return ErrorHandler.handleError(activeServerConfig, 413, "Payload too large");
        }

        String relativePath = calculateRelativePath(path, matchedRoute.getPath());

        return switch (method) {
            case "GET" -> StaticFileHandler.handleRequest(request, matchedRoute.getRoot(), matchedRoute.getDefaultFile(), relativePath);
            case "POST" -> handlePost(request, matchedRoute, activeServerConfig);
            case "DELETE" -> handleDelete(request, matchedRoute, activeServerConfig);
            case "HEAD" -> {
                HttpResponse response = StaticFileHandler.handleRequest(request, matchedRoute.getRoot(), matchedRoute.getDefaultFile(), relativePath);
                response.setBody(new byte[0]);
                yield response;
            }
            default -> ErrorHandler.handleError(activeServerConfig, 501, "Method not implemented");
        };
    }

    private ServerConfig resolveVirtualHost(HttpRequest request) {
        String hostHeader = request.getHeader("host");
        
        if (hostHeader != null) {
            String requestedHost = hostHeader.split(":")[0];
            for (ServerConfig config : serverConfigs) {
                if (config.getServerName() != null && config.getServerName().equals(requestedHost)) {
                    return config;
                }
            }
        }

        for (ServerConfig config : serverConfigs) {
            if (config.getDefaultServer() != null && config.getDefaultServer()) {
                return config;
            }
        }

        return serverConfigs.get(0);
    }

    private RouteConfig findMatchingRoute(ServerConfig config, String path) {
        RouteConfig bestMatch = null;
        int bestMatchLength = 0;

        List<RouteConfig> routes = config.getRoutes();
        if (routes == null) return null;

        for (RouteConfig route : routes) {
            String routePath = route.getPath();
            
            if (path.equals(routePath)) {
                return route;
            }
            
            if (path.startsWith(routePath + "/") || (routePath.equals("/") && path.startsWith("/"))) {
                if (routePath.length() > bestMatchLength) {
                    bestMatch = route;
                    bestMatchLength = routePath.length();
                }
            }
        }

        return bestMatch;
    }

    private String calculateRelativePath(String requestPath, String routePath) {
        if (requestPath.equals(routePath)) {
            return "";
        }
        if (routePath.equals("/")) {
            return requestPath.startsWith("/") ? requestPath.substring(1) : requestPath;
        }
        if (requestPath.startsWith(routePath + "/")) {
            return requestPath.substring(routePath.length() + 1);
        }
        return "";
    }

    private HttpResponse handlePost(HttpRequest request, RouteConfig route, ServerConfig config) {
        HttpResponse response = new HttpResponse();

        byte[] body = request.getBody();
        if (body.length == 0) {
            return ErrorHandler.handleError(config, 400, "Empty request body");
        }

        String filename = extractFilename(request.getHeader("content-disposition"));
        if (filename == null) {
            filename = "upload_" + System.currentTimeMillis();
        }

        String uploadPath = route.getRoot() + "/" + filename;
        try {
            java.nio.file.Files.write(java.nio.file.Paths.get(uploadPath), body);
            response.setStatusCode(201);
            response.setBody("File uploaded successfully: " + filename);
            response.setHeader("content-type", "text/plain");
        } catch (Exception e) {
            return ErrorHandler.handleError(config, 500, "Upload failed");
        }

        return response;
    }

    private HttpResponse handleDelete(HttpRequest request, RouteConfig route, ServerConfig config) {
        HttpResponse response = new HttpResponse();

        String path = request.getPath();
        if (path.startsWith(route.getPath())) {
            path = path.substring(route.getPath().length());
        }

        String filePath = route.getRoot() + "/" + path;
        try {
            java.nio.file.Files.delete(java.nio.file.Paths.get(filePath));
            response.setStatusCode(204);
            response.setBody(new byte[0]);
        } catch (Exception e) {
            return ErrorHandler.handleError(config, 404, "File not found");
        }

        return response;
    }

    private HttpResponse handleRedirect(RouteConfig route) {
        HttpResponse response = new HttpResponse();
        Map<String, Object> redirect = route.getRedirect();

        Number statusNum = (Number) redirect.get("status");
        String url = (String) redirect.get("url");

        if (statusNum != null) {
            response.setStatusCode(statusNum.intValue());
        }

        if (url != null) {
            response.setHeader("location", url);
        }

        return response;
    }

    private String extractFilename(String contentDisposition) {
        if (contentDisposition == null) {
            return null;
        }

        String[] parts = contentDisposition.split(";");
        for (String part : parts) {
            part = part.trim();
            if (part.startsWith("filename=")) {
                return part.substring(9).replace("\"", "");
            }
        }

        return null;
    }
}