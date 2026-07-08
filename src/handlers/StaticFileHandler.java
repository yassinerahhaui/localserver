package handlers;

import http.HttpRequest;
import http.HttpResponse;
import config.ServerConfig;
import error.ErrorHandler;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

public class StaticFileHandler {
    private static final Map<String, String> MIME_TYPES = new HashMap<>();

    static {
        MIME_TYPES.put("html", "text/html");
        MIME_TYPES.put("htm", "text/html");
        MIME_TYPES.put("css", "text/css");
        MIME_TYPES.put("js", "application/javascript");
        MIME_TYPES.put("json", "application/json");
        MIME_TYPES.put("xml", "application/xml");
        MIME_TYPES.put("txt", "text/plain");
        MIME_TYPES.put("jpg", "image/jpeg");
        MIME_TYPES.put("jpeg", "image/jpeg");
        MIME_TYPES.put("png", "image/png");
        MIME_TYPES.put("gif", "image/gif");
        MIME_TYPES.put("svg", "image/svg+xml");
        MIME_TYPES.put("ico", "image/x-icon");
        MIME_TYPES.put("pdf", "application/pdf");
        MIME_TYPES.put("zip", "application/zip");
        MIME_TYPES.put("mp3", "audio/mpeg");
        MIME_TYPES.put("mp4", "video/mp4");
        MIME_TYPES.put("webm", "video/webm");
    }

    public static HttpResponse handleRequest(HttpRequest request, String root, String defaultFile) {
        return handleRequest(null, request, root, defaultFile, null, false);
    }

    public static HttpResponse handleRequest(HttpRequest request, String root, String defaultFile, String relativePath) {
        return handleRequest(null, request, root, defaultFile, relativePath, false);
    }

    public static HttpResponse handleRequest(HttpRequest request, String root, String defaultFile, String relativePath, Boolean directoryListing) {
        return handleRequest(null, request, root, defaultFile, relativePath, directoryListing);
    }

    public static HttpResponse handleRequest(ServerConfig serverConfig, HttpRequest request, String root, String defaultFile, String relativePath, Boolean directoryListing) {
        HttpResponse response = new HttpResponse();

        String path;
        if (relativePath != null && !relativePath.isEmpty()) {
            path = relativePath;
        } else if (relativePath != null && relativePath.isEmpty()) {
            // Empty relative path means request is for route root
            path = "";
        } else {
            // Legacy: calculate path from request if relativePath not provided
            path = request.getPath();
            // Remove leading slash
            if (path.startsWith("/")) {
                path = path.substring(1);
            }
        }

        String filePath;
        if (path.isEmpty()) {
            // Requesting root of this route
            filePath = root;
        } else {
            filePath = root + File.separator + path;
        }
        filePath = canonicalizePath(filePath);

        // Security check: ensure file is within root
        String canonicalRoot = canonicalizePath(root);
        if (!filePath.startsWith(canonicalRoot)) {
            return ErrorHandler.handleError(serverConfig, 403, "Forbidden", request);
        }

        File file = new File(filePath);

        // If it's a directory, look for default file
        if (file.isDirectory()) {
            if (defaultFile != null && !defaultFile.isEmpty()) {
                File defaultFilePath = new File(filePath + File.separator + defaultFile);
                if (defaultFilePath.exists() && defaultFilePath.isFile()) {
                    return serveFile(response, defaultFilePath);
                }
            }
            
            if (directoryListing != null && directoryListing) {
                return serveDirectoryListing(response, file, request);
            }

            return ErrorHandler.handleError(serverConfig, 404, "Not Found", request);
        }


        // If file exists and is a file, serve it
        if (file.exists() && file.isFile()) {
            return serveFile(response, file);
        }

        return ErrorHandler.handleError(serverConfig, 404, "Not Found", request);
    }

    private static HttpResponse serveDirectoryListing(HttpResponse response, File directory, HttpRequest request) {
        File[] files = directory.listFiles();
        if (files == null) {
            response.setStatusCode(500);
            response.setBody("Internal Server Error: Cannot list directory");
            return response;
        }

        String acceptHeader = request.getHeader("accept");
        boolean wantJson = (acceptHeader != null && acceptHeader.contains("application/json")) 
                || "json".equals(request.getQueryParam("format"));

        if (wantJson) {
            StringBuilder json = new StringBuilder("[");
            boolean first = true;
            for (File f : files) {
                if (!first) {
                    json.append(",");
                }
                json.append("{\"name\":\"").append(f.getName()).append("\",");
                json.append("\"isDir\":").append(f.isDirectory()).append(",");
                json.append("\"size\":").append(f.isDirectory() ? 0 : f.length()).append("}");
                first = false;
            }
            json.append("]");
            response.setStatusCode(200);
            response.setHeader("content-type", "application/json");
            response.setBody(json.toString().getBytes());
        } else {
            // Serve a beautiful HTML page listing files
            StringBuilder html = new StringBuilder();
            html.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><title>Index of ").append(directory.getName()).append("</title>");
            html.append("<style>body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background-color: #f8f9fa; color: #333; padding: 40px; } ");
            html.append("h1 { color: #1e3c72; border-bottom: 2px solid #61dafb; padding-bottom: 10px; } ");
            html.append("ul { list-style: none; padding: 0; } ");
            html.append("li { margin: 12px 0; background: white; padding: 15px; border-radius: 8px; box-shadow: 0 2px 5px rgba(0,0,0,0.05); display: flex; justify-content: space-between; align-items: center; } ");
            html.append("a { color: #2a5298; text-decoration: none; font-weight: bold; } a:hover { color: #61dafb; } ");
            html.append(".meta { color: #888; font-size: 0.9rem; }</style></head><body>");
            html.append("<h1>Index of ").append(directory.getName()).append("</h1><ul>");
            String requestPath = request.getPath();
            if (!requestPath.endsWith("/")) {
                requestPath += "/";
            }
            html.append("<li><a href=\"").append(requestPath).append("../\">📁 .. (Parent Directory)</a></li>");
            for (File f : files) {
                String name = f.getName();
                html.append("<li>");
                html.append("<a href=\"").append(requestPath).append(name).append(f.isDirectory() ? "/" : "").append("\">");
                html.append(f.isDirectory() ? "📁 " : "📄 ").append(name).append("</a>");
                if (!f.isDirectory()) {
                    html.append("<span class=\"meta\">").append(f.length()).append(" bytes</span>");
                }
                html.append("</li>");
            }
            html.append("</ul></body></html>");
            response.setStatusCode(200);
            response.setHeader("content-type", "text/html");
            response.setBody(html.toString().getBytes());
        }
        return response;
    }

    private static HttpResponse serveFile(HttpResponse response, File file) {
        String mimeType = getMimeType(file.getName());
        response.setStatusCode(200);
        response.setHeader("content-type", mimeType);
        response.setFile(file);
        return response;
    }

    private static String getMimeType(String filename) {
        int lastDot = filename.lastIndexOf('.');
        if (lastDot > 0) {
            String ext = filename.substring(lastDot + 1).toLowerCase();
            return MIME_TYPES.getOrDefault(ext, "application/octet-stream");
        }
        return "application/octet-stream";
    }

    private static String canonicalizePath(String path) {
        try {
            return new File(path).getCanonicalPath();
        } catch (IOException e) {
            return path;
        }
    }
}
