package cgi;

import http.HttpRequest;
import http.HttpResponse;
import config.RouteConfig;

import java.io.*;
import java.util.Map;

public class CgiHandler {

    public static Process startCgiProcess(HttpRequest request, RouteConfig route, String root, String host, int port) throws Exception {
        String path = request.getPath();
        if (path.startsWith(route.getPath())) {
            path = path.substring(route.getPath().length());
        }

        if (path.startsWith("/")) {
            path = path.substring(1);
        }

        String filePath = root + File.separator + path;
        String extension = getFileExtension(filePath);

        String interpreter = route.getCgiInterpreter(extension);
        if (interpreter == null) {
            throw new Exception("403 Forbidden: CGI extension not allowed");
        }

        File scriptFile = new File(filePath);
        if (!scriptFile.exists()) {
            throw new Exception("404 Not Found: Script not found");
        }

        ProcessBuilder pb = new ProcessBuilder(interpreter, scriptFile.getAbsolutePath());
        
        // Ensure correct relative path handling by running process in script's parent directory
        pb.directory(scriptFile.getParentFile());

        Map<String, String> env = pb.environment();
        env.put("REQUEST_METHOD", request.getMethod());
        env.put("QUERY_STRING", extractQueryString(request.getUri()));
        env.put("PATH_INFO", scriptFile.getAbsolutePath());
        env.put("SCRIPT_NAME", route.getPath() + (route.getPath().endsWith("/") ? "" : "/") + path);
        env.put("SERVER_NAME", host != null ? host : "localhost");
        env.put("SERVER_PORT", String.valueOf(port));
        env.put("SERVER_PROTOCOL", request.getHttpVersion());
        env.put("CONTENT_LENGTH", String.valueOf(request.getBody().length));
        env.put("CONTENT_TYPE", request.getContentType() != null ? request.getContentType() : "");

        for (Map.Entry<String, String> header : request.getHeaders().entrySet()) {
            String cgVar = "HTTP_" + header.getKey().toUpperCase().replace("-", "_");
            env.put(cgVar, header.getValue());
        }

        pb.redirectErrorStream(true);
        Process process = pb.start();

        if (request.getBody().length > 0) {
            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write(request.getBody());
                stdin.flush();
            }
        } else {
            process.getOutputStream().close();
        }

        return process;
    }

    public static void parseCgiResponse(HttpResponse response, byte[] output) {
        String outputStr = new String(output);
        int doubleNewlineIndex = outputStr.indexOf("\r\n\r\n");
        if (doubleNewlineIndex < 0) {
            doubleNewlineIndex = outputStr.indexOf("\n\n");
        }

        if (doubleNewlineIndex > 0) {
            String headerPart = outputStr.substring(0, doubleNewlineIndex);
            String bodyPart = outputStr.substring(doubleNewlineIndex + (outputStr.indexOf("\r\n\r\n") >= 0 ? 4 : 2));

            String[] headerLines = headerPart.split("\r?\n");
            for (String line : headerLines) {
                if (line.trim().isEmpty()) continue;
                int colonIndex = line.indexOf(':');
                if (colonIndex > 0) {
                    String name = line.substring(0, colonIndex).trim();
                    String value = line.substring(colonIndex + 1).trim();
                    
                    if (name.equalsIgnoreCase("status")) {
                        String[] parts = value.split(" ", 2);
                        try {
                            response.setStatusCode(Integer.parseInt(parts[0]));
                        } catch (NumberFormatException e) {
                            // Proceed with default
                        }
                    } else if (name.equalsIgnoreCase("content-type")) {
                        response.setHeader("content-type", value);
                    } else {
                        response.setHeader(name, value);
                    }
                }
            }
            response.setBody(bodyPart);
        } else {
            response.setStatusCode(200);
            response.setHeader("content-type", "text/plain");
            response.setBody(outputStr);
        }
    }

    private static String getFileExtension(String filename) {
        int lastDot = filename.lastIndexOf('.');
        return lastDot > 0 ? filename.substring(lastDot) : "";
    }

    private static String extractQueryString(String uri) {
        int questionIndex = uri.indexOf('?');
        return questionIndex >= 0 ? uri.substring(questionIndex + 1) : "";
    }
}