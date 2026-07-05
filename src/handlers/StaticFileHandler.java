package handlers;

import http.HttpRequest;
import http.HttpResponse;

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
        return handleRequest(request, root, defaultFile, null);
    }

    public static HttpResponse handleRequest(HttpRequest request, String root, String defaultFile, String relativePath) {
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
            response.setStatusCode(403);
            response.setBody("Forbidden");
            return response;
        }

        File file = new File(filePath);

        // If it's a directory, look for default file
        if (file.isDirectory()) {
            if (defaultFile != null) {
                File defaultFilePath = new File(filePath + File.separator + defaultFile);
                if (defaultFilePath.exists() && defaultFilePath.isFile()) {
                    return serveFile(response, defaultFilePath);
                }
            }
            response.setStatusCode(404);
            response.setBody("Not Found");
            return response;
        }


        // If file exists and is a file, serve it
        if (file.exists() && file.isFile()) {
            return serveFile(response, file);
        }

        response.setStatusCode(404);
        response.setBody("Not Found");
        return response;
    }

    private static HttpResponse serveFile(HttpResponse response, File file) {
        try {
            byte[] content = Files.readAllBytes(file.toPath());
            String mimeType = getMimeType(file.getName());
            
            response.setStatusCode(200);
            response.setHeader("content-type", mimeType);
            response.setBody(content);
            
            return response;
        } catch (IOException e) {
            response.setStatusCode(500);
            response.setBody("Internal Server Error");
            return response;
        }
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
