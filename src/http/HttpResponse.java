package http;

import java.nio.charset.StandardCharsets;
import java.util.*;

public class HttpResponse {
    private int statusCode;
    private String statusMessage;
    private Map<String, String> headers;
    private Map<String, String> cookies;
    private byte[] body;
    private boolean isHeadersSent = false;

    public HttpResponse() {
        this.statusCode = 200;
        this.statusMessage = "OK";
        this.headers = new HashMap<>();
        this.cookies = new HashMap<>();
        this.body = new byte[0];
        
        // Default headers
        headers.put("server", "CustomServer/1.0");
        headers.put("connection", "close");
    }

    public int getStatusCode() {
        return statusCode;
    }

    public void setStatusCode(int code) {
        this.statusCode = code;
        this.statusMessage = getStatusMessage(code);
    }

    public String getStatusMessage() {
        return statusMessage;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public void setHeader(String name, String value) {
        headers.put(name.toLowerCase(), value);
    }

    public String getHeader(String name) {
        return headers.get(name.toLowerCase());
    }

    public void addCookie(String name, String value) {
        cookies.put(name, value);
    }

    public void addCookie(String name, String value, long maxAge) {
        String cookie = name + "=" + value + "; Max-Age=" + maxAge;
        cookies.put(name, cookie);
    }

    public byte[] getBody() {
        return body;
    }

    public void setBody(byte[] body) {
        this.body = body != null ? body : new byte[0];
        setHeader("content-length", String.valueOf(this.body.length));
    }

    public void setBody(String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        setBody(bytes);
    }

    public byte[] toBytes() {
        StringBuilder statusLine = new StringBuilder();
        statusLine.append("HTTP/1.1 ").append(statusCode).append(" ").append(statusMessage).append("\r\n");

        // Add headers
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            statusLine.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
        }

        // Add cookies
        for (Map.Entry<String, String> entry : cookies.entrySet()) {
            statusLine.append("Set-Cookie: ").append(entry.getValue()).append("\r\n");
        }

        statusLine.append("\r\n");

        byte[] headerBytes = statusLine.toString().getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[headerBytes.length + body.length];
        System.arraycopy(headerBytes, 0, result, 0, headerBytes.length);
        System.arraycopy(body, 0, result, headerBytes.length, body.length);

        return result;
    }

    private static String getStatusMessage(int code) {
        return switch (code) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 204 -> "No Content";
            case 301 -> "Moved Permanently";
            case 302 -> "Found";
            case 304 -> "Not Modified";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 413 -> "Payload Too Large";
            case 500 -> "Internal Server Error";
            case 501 -> "Not Implemented";
            case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable";
            default -> "Unknown";
        };
    }

    @Override
    public String toString() {
        return "HTTP/1.1 " + statusCode + " " + statusMessage;
    }
}
