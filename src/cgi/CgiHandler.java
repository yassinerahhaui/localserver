package cgi;

import http.HttpRequest;
import http.HttpResponse;
import config.RouteConfig;

import java.io.*;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class CgiHandler {

    public static HttpResponse handleCgiRequest(HttpRequest request, RouteConfig route, String root) {
        HttpResponse response = new HttpResponse();

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
            response.setStatusCode(403);
            response.setBody("CGI execution not allowed");
            return response;
        }

        try {
            File scriptFile = new File(filePath);
            if (!scriptFile.exists()) {
                response.setStatusCode(404);
                response.setBody("Script not found");
                return response;
            }

            ProcessBuilder pb = new ProcessBuilder(interpreter, scriptFile.getAbsolutePath());

            Map<String, String> env = pb.environment();
            env.put("REQUEST_METHOD", request.getMethod());
            env.put("QUERY_STRING", extractQueryString(request.getUri()));
            env.put("PATH_INFO", request.getPath());
            env.put("SCRIPT_NAME", route.getPath());
            env.put("SERVER_NAME", "localhost");
            env.put("SERVER_PORT", "8080"); 
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
                }
            }

            ByteArrayOutputStream result = new ByteArrayOutputStream();
            try (InputStream stdout = process.getInputStream()) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                
                boolean finished = false;
                long endTime = System.currentTimeMillis() + 500; 

                while (System.currentTimeMillis() < endTime) {
                    while (stdout.available() > 0 && (bytesRead = stdout.read(buffer)) != -1) {
                        result.write(buffer, 0, bytesRead);
                    }
                    
                    try {
                        process.exitValue();
                        finished = true;
                        
                        while ((bytesRead = stdout.read(buffer)) != -1) {
                            result.write(buffer, 0, bytesRead);
                        }
                        break;
                    } catch (IllegalThreadStateException e) {
                        Thread.sleep(10);
                    }
                }

                if (!finished) {
                    process.destroyForcibly();
                    response.setStatusCode(504);
                    response.setBody("Gateway Timeout");
                    return response;
                }
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                response.setStatusCode(500);
                response.setBody("CGI Script Error (exit code: " + exitCode + ")");
                return response;
            }

            parseCgiResponse(response, result.toByteArray());
            return response;

        } catch (Exception e) {
            response.setStatusCode(500);
            response.setBody("Internal Server Error");
            return response;
        }
    }

    private static void parseCgiResponse(HttpResponse response, byte[] output) {
        String outputStr = new String(output);
        int doubleNewlineIndex = outputStr.indexOf("\r\n\r\n");
        if (doubleNewlineIndex < 0) {
            doubleNewlineIndex = outputStr.indexOf("\n\n");
        }

        if (doubleNewlineIndex > 0) {
            String headerPart = outputStr.substring(0, doubleNewlineIndex);
            String bodyPart = outputStr.substring(doubleNewlineIndex + 4);

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