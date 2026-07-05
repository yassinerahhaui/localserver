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
    private ServerConfig serverConfig;

    public Router(ServerConfig serverConfig) {
        this.serverConfig = serverConfig;
    }

    public HttpResponse route(HttpRequest request) {
        String path = request.getPath();
        String method = request.getMethod();

        // Find matching route
        RouteConfig matchedRoute = findMatchingRoute(path, method);

        if (matchedRoute == null) {
            return ErrorHandler.handleError(serverConfig, 404, "Route not found: " + path);
        }

        // Check if method is allowed
        if (!matchedRoute.getMethods().contains(method)) {
            return ErrorHandler.handleError(serverConfig, 405, "Method " + method + " not allowed for " + path);
        }

        // Handle redirects
        if (matchedRoute.isRedirect()) {
            return handleRedirect(matchedRoute);
        }

        // Handle CGI
        if (matchedRoute.hasCgi()) {
            return CgiHandler.handleCgiRequest(request, matchedRoute, matchedRoute.getRoot());
        }

        // Check content length
        if (request.getContentLength() > matchedRoute.getClientMaxBodySize()) {
            return ErrorHandler.handleError(serverConfig, 413, "Payload too large");
        }

        // Calculate relative path from route
        String relativePath = calculateRelativePath(path, matchedRoute.getPath());

        // Handle based on HTTP method
        return switch (method) {
            case "GET" -> StaticFileHandler.handleRequest(request, matchedRoute.getRoot(), matchedRoute.getDefaultFile(), relativePath);
            case "POST" -> handlePost(request, matchedRoute);
            case "DELETE" -> handleDelete(request, matchedRoute);
            case "HEAD" -> {
                HttpResponse response = StaticFileHandler.handleRequest(request, matchedRoute.getRoot(), matchedRoute.getDefaultFile(), relativePath);
                // For HEAD, return headers only (no body)
                response.setBody(new byte[0]);
                yield response;
            }
            default -> ErrorHandler.handleError(serverConfig, 501, "Method not implemented");
        };
    }

    private String calculateRelativePath(String requestPath, String routePath) {
        if (requestPath.equals(routePath)) {
            // Exact match - return empty path to serve root
            return "";
        }
        if (routePath.equals("/")) {
            // Root route - return path as-is (without leading slash)
            return requestPath.startsWith("/") ? requestPath.substring(1) : requestPath;
        }
        // Remove route prefix from request path
        if (requestPath.startsWith(routePath + "/")) {
            return requestPath.substring(routePath.length() + 1);
        }
        return "";
    }

    private RouteConfig findMatchingRoute(String path, String method) {
        RouteConfig bestMatch = null;
        int bestMatchLength = 0;

        List<RouteConfig> routes = serverConfig.getRoutes();
        for (RouteConfig route : routes) {
            String routePath = route.getPath();
            
            // Exact match or prefix match
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

    private HttpResponse handlePost(HttpRequest request, RouteConfig route) {
        // Handle file upload
        HttpResponse response = new HttpResponse();

        if (!route.getMethods().contains("POST")) {
            return ErrorHandler.handleError(serverConfig, 405, "POST not allowed");
        }

        byte[] body = request.getBody();
        if (body.length == 0) {
            response.setStatusCode(400);
            response.setBody("Empty request body");
            return response;
        }

        // Save file
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
            response.setStatusCode(500);
            response.setBody("Upload failed: " + e.getMessage());
        }

        return response;
    }

    private HttpResponse handleDelete(HttpRequest request, RouteConfig route) {
        HttpResponse response = new HttpResponse();

        if (!route.getMethods().contains("DELETE")) {
            return ErrorHandler.handleError(serverConfig, 405, "DELETE not allowed");
        }

        String path = request.getPath();
        if (path.startsWith(route.getPath())) {
            path = path.substring(route.getPath().length());
        }

        String filePath = route.getRoot() + "/" + path;
        try {
            java.nio.file.Files.delete(java.nio.file.Paths.get(filePath));
            response.setStatusCode(204);
            response.setBody("");
        } catch (Exception e) {
            response.setStatusCode(404);
            response.setBody("File not found");
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
