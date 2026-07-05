package http;

import java.nio.charset.StandardCharsets;

/**
 * Parses HTTP requests from raw bytes
 */
public class HttpParser {
    private byte[] data;
    private int pos;
    private int dataLength;

    public HttpParser() {
        this.data = new byte[0];
        this.pos = 0;
        this.dataLength = 0;
    }

    public void appendData(byte[] newData, int length) {
        if (dataLength + length > data.length) {
            byte[] newBuffer = new byte[Math.max(dataLength + length, data.length * 2)];
            System.arraycopy(data, 0, newBuffer, 0, dataLength);
            data = newBuffer;
        }
        System.arraycopy(newData, 0, data, dataLength, length);
        dataLength += length;
    }

    public boolean hasCompleteRequest() {
        // Check for \r\n\r\n (end of headers)
        return findHeaderEnd() >= 0;
    }

    private int findHeaderEnd() {
        for (int i = 0; i < dataLength - 3; i++) {
            if (data[i] == '\r' && data[i + 1] == '\n' && data[i + 2] == '\r' && data[i + 3] == '\n') {
                return i;
            }
        }
        return -1;
    }

    public HttpRequest parseRequest() throws Exception {
        int headerEnd = findHeaderEnd();
        if (headerEnd < 0) {
            throw new Exception("Incomplete request");
        }

        String headerStr = new String(data, 0, headerEnd, StandardCharsets.UTF_8);
        String[] lines = headerStr.split("\r\n");

        if (lines.length < 1) {
            throw new Exception("Invalid request");
        }

        HttpRequest request = new HttpRequest();

        // Parse request line
        String[] requestLine = lines[0].split(" ", 3);
        if (requestLine.length != 3) {
            throw new Exception("Invalid request line");
        }

        request.setMethod(requestLine[0].toUpperCase());
        request.setUri(requestLine[1]);
        request.setHttpVersion(requestLine[2]);

        // Parse headers
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            int colonIndex = line.indexOf(':');
            if (colonIndex > 0) {
                String headerName = line.substring(0, colonIndex).trim();
                String headerValue = line.substring(colonIndex + 1).trim();
                request.addHeader(headerName, headerValue);
            }
        }

        request.parseCookies();

        // Parse body if present
        long contentLength = request.getContentLength();
        int bodyStart = headerEnd + 4;

        if (contentLength > 0) {
            int bodyEnd = bodyStart + (int) contentLength;
            if (bodyEnd <= dataLength) {
                byte[] body = new byte[(int) contentLength];
                System.arraycopy(data, bodyStart, body, 0, (int) contentLength);
                request.setBody(body);
            }
        }

        return request;
    }

    public void reset() {
        data = new byte[0];
        pos = 0;
        dataLength = 0;
    }

    public int getDataLength() {
        return dataLength;
    }
}
