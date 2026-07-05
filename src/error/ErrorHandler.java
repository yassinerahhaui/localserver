package error;

import http.HttpResponse;
import config.ServerConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

public class ErrorHandler {

    public static HttpResponse handleError(ServerConfig serverConfig, int statusCode, String defaultMessage) {
        HttpResponse response = new HttpResponse();
        response.setStatusCode(statusCode);
        response.setHeader("content-type", "text/html");

        // Try to load custom error page
        if (serverConfig != null) {
            String errorPagePath = serverConfig.getErrorPages().get(String.valueOf(statusCode));
            if (errorPagePath != null) {
                try {
                    byte[] content = Files.readAllBytes(Paths.get(errorPagePath));
                    response.setBody(content);
                    return response;
                } catch (IOException e) {
                    // Fall through to default message
                }
            }
        }

        // Use default error page
        String htmlContent = generateDefaultErrorPage(statusCode, defaultMessage);
        response.setBody(htmlContent);
        return response;
    }

    private static String generateDefaultErrorPage(int statusCode, String message) {
        String statusText = getStatusText(statusCode);
        return "<!DOCTYPE html>\n" +
                "<html>\n" +
                "<head>\n" +
                "    <title>" + statusCode + " " + statusText + "</title>\n" +
                "    <style>\n" +
                "        body { font-family: Arial, sans-serif; margin: 40px; }\n" +
                "        h1 { color: #333; }\n" +
                "        p { color: #666; }\n" +
                "    </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "    <h1>" + statusCode + " " + statusText + "</h1>\n" +
                "    <p>" + message + "</p>\n" +
                "</body>\n" +
                "</html>";
    }

    private static String getStatusText(int code) {
        return switch (code) {
            case 400 -> "Bad Request";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 413 -> "Payload Too Large";
            case 500 -> "Internal Server Error";
            case 501 -> "Not Implemented";
            case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable";
            case 504 -> "Gateway Timeout";
            default -> "Unknown Error";
        };
    }
}
