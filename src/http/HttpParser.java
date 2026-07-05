package http;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public class HttpParser {
    private byte[] data;
    private int pos;
    private int dataLength;
    private int cachedHeaderEnd;
    private long expectedBodyLength;
    private boolean isChunked;
    private boolean headersParsed;

    private static final int MAX_HEADER_SIZE = 8192;

    public HttpParser() {
        reset();
    }

    public void appendData(byte[] newData, int length) throws Exception {
        if (!headersParsed && (dataLength + length > MAX_HEADER_SIZE)) {
            throw new Exception("413 Payload Too Large");
        }

        if (dataLength + length > data.length) {
            byte[] newBuffer = new byte[Math.max(dataLength + length, data.length * 2)];
            System.arraycopy(data, 0, newBuffer, 0, dataLength);
            data = newBuffer;
        }
        
        System.arraycopy(newData, 0, data, dataLength, length);
        dataLength += length;
    }

    public boolean hasCompleteRequest() {
        if (cachedHeaderEnd < 0) {
            cachedHeaderEnd = findHeaderEnd();
        }

        if (cachedHeaderEnd < 0) {
            return false;
        }

        if (!headersParsed) {
            parseHeaderMetadata();
            headersParsed = true;
        }

        int bodyStart = cachedHeaderEnd + 4;

        if (isChunked) {
            for (int i = bodyStart; i <= dataLength - 5; i++) {
                if (data[i] == '0' && data[i + 1] == '\r' && data[i + 2] == '\n' && data[i + 3] == '\r' && data[i + 4] == '\n') {
                    if (i == bodyStart || data[i - 1] == '\n') {
                        return true;
                    }
                }
            }
            return false;
        } else {
            return dataLength >= bodyStart + expectedBodyLength;
        }
    }

    private void parseHeaderMetadata() {
        String headerStr = new String(data, 0, cachedHeaderEnd, StandardCharsets.UTF_8);
        String[] lines = headerStr.split("\r\n");
        expectedBodyLength = 0;

        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].toLowerCase();
            if (line.startsWith("content-length:")) {
                try {
                    expectedBodyLength = Long.parseLong(line.substring(line.indexOf(':') + 1).trim());
                } catch (NumberFormatException e) {
                    // Keep default 0
                }
            } else if (line.startsWith("transfer-encoding:") && line.contains("chunked")) {
                isChunked = true;
            }
        }
    }

    private int findHeaderEnd() {
        for (int i = 0; i < dataLength - 3; i++) {
            if (data[i] == '\r' && data[i + 1] == '\n' && data[i + 2] == '\r' && data[i + 3] == '\n') {
                return i;
            }
        }
        return -1;
    }

    private int findCRLF(byte[] array, int start) {
        for (int i = start; i < dataLength - 1; i++) {
            if (array[i] == '\r' && array[i + 1] == '\n') {
                return i;
            }
        }
        return -1;
    }

    public HttpRequest parseRequest() throws Exception {
        if (cachedHeaderEnd < 0) {
            cachedHeaderEnd = findHeaderEnd();
        }
        
        if (cachedHeaderEnd < 0) {
            throw new Exception("Incomplete request");
        }

        String headerStr = new String(data, 0, cachedHeaderEnd, StandardCharsets.UTF_8);
        String[] lines = headerStr.split("\r\n");

        if (lines.length < 1) {
            throw new Exception("Invalid request");
        }

        HttpRequest request = new HttpRequest();

        String[] requestLine = lines[0].split(" ", 3);
        if (requestLine.length != 3) {
            throw new Exception("Invalid request line");
        }

        request.setMethod(requestLine[0].toUpperCase());
        request.setUri(requestLine[1]);
        request.setHttpVersion(requestLine[2]);

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

        int bodyStart = cachedHeaderEnd + 4;

        if (request.isChunked()) {
            ByteArrayOutputStream chunkedBody = new ByteArrayOutputStream();
            int currentPos = bodyStart;

            while (currentPos < dataLength) {
                int crlfIndex = findCRLF(data, currentPos);
                if (crlfIndex == -1) {
                    break;
                }

                String hexSize = new String(data, currentPos, crlfIndex - currentPos, StandardCharsets.UTF_8).trim();
                if (hexSize.isEmpty()) {
                    currentPos = crlfIndex + 2;
                    continue;
                }

                try {
                    int chunkSize = Integer.parseInt(hexSize, 16);
                    if (chunkSize == 0) {
                        break;
                    }

                    currentPos = crlfIndex + 2;
                    if (currentPos + chunkSize <= dataLength) {
                        chunkedBody.write(data, currentPos, chunkSize);
                        currentPos += chunkSize + 2;
                    } else {
                        break;
                    }
                } catch (NumberFormatException e) {
                    break;
                }
            }
            request.setBody(chunkedBody.toByteArray());
            
        } else {
            long contentLength = request.getContentLength();
            if (contentLength > 0) {
                int bodyEnd = bodyStart + (int) contentLength;
                if (bodyEnd <= dataLength) {
                    byte[] body = new byte[(int) contentLength];
                    System.arraycopy(data, bodyStart, body, 0, (int) contentLength);
                    request.setBody(body);
                }
            }
        }

        return request;
    }

    public void reset() {
        data = new byte[0];
        pos = 0;
        dataLength = 0;
        cachedHeaderEnd = -1;
        expectedBodyLength = 0;
        isChunked = false;
        headersParsed = false;
    }

    public int getDataLength() {
        return dataLength;
    }
}