package cgi;

import http.HttpRequest;
import http.HttpResponse;
import config.RouteConfig;

import java.io.*;
import java.util.Map;

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

            // Set up environment variables
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

            // Pass HTTP headers as CGI variables
            for (Map.Entry<String, String> header : request.getHeaders().entrySet()) {
                String cgVar = "HTTP_" + header.getKey().toUpperCase().replace("-", "_");
                env.put(cgVar, header.getValue());
            }

            pb.redirectErrorStream(true);
            Process process = pb.start();

            // Write body if POST/PUT
            if (request.getBody().length > 0) {
                OutputStream stdin = process.getOutputStream();
                stdin.write(request.getBody());
                stdin.close();
            }

            // Read output
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            InputStream stdout = process.getInputStream();
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = stdout.read(buffer)) != -1) {
                result.write(buffer, 0, bytesRead);
            }

            // Wait for process to finish (with timeout)
            boolean finished = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                response.setStatusCode(504);
                response.setBody("Gateway Timeout");
                return response;
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                response.setStatusCode(500);
                response.setBody("CGI Script Error (exit code: " + exitCode + ")");
                return response;
            }

            byte[] output = result.toByteArray();
            
            // Parse CGI response (should include headers)
            parseCgiResponse(response, output);

            return response;

        } catch (Exception e) {
            e.printStackTrace();
            response.setStatusCode(500);
            response.setBody("Internal Server Error: " + e.getMessage());
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

            // Parse headers
            String[] headerLines = headerPart.split("\r?\n");
            for (String line : headerLines) {
                if (line.isEmpty()) continue;
                int colonIndex = line.indexOf(':');
                if (colonIndex > 0) {
                    String name = line.substring(0, colonIndex).trim();
                    String value = line.substring(colonIndex + 1).trim();
                    
                    if (name.equalsIgnoreCase("status")) {
                        // Parse status like "200 OK"
                        String[] parts = value.split(" ", 2);
                        try {
                            response.setStatusCode(Integer.parseInt(parts[0]));
                        } catch (NumberFormatException e) {
                            // Ignore
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
            // No headers, just body
            response.setStatusCode(200);
            response.setHeader("content-type", "text/plain");
            response.setBody(outputStr);
        }
    }

    private static String getFileExtension(String filename) {
        int lastDot = filename.lastIndexOf('.');
        if (lastDot > 0) {
            return filename.substring(lastDot);
        }
        return "";
    }

    private static String extractQueryString(String uri) {
        int questionIndex = uri.indexOf('?');
        if (questionIndex >= 0) {
            return uri.substring(questionIndex + 1);
        }
        return "";
    }
}
