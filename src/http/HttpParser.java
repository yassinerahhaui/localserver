package http;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class HttpParser {
    private byte[] data;
    private int dataLength;
    private int cachedHeaderEnd;
    private long expectedBodyLength;
    private boolean isChunked;
    private boolean headersParsed;
    private HttpRequest parsedRequest;

    private static final int MAX_HEADER_SIZE = 8192;

    public HttpParser() {
        reset();
    }

    public void reset() {
        data = new byte[0];
        dataLength = 0;
        cachedHeaderEnd = -1;
        expectedBodyLength = 0;
        isChunked = false;
        headersParsed = false;
        parsedRequest = null;
    }

    public void appendData(byte[] newData, int length) throws Exception {
        if (!headersParsed && (dataLength + length > MAX_HEADER_SIZE)) {
            throw new Exception("400 Bad Request: Headers too large");
        }

        if (dataLength + length > data.length) {
            byte[] newBuffer = new byte[Math.max(dataLength + length, data.length * 2)];
            System.arraycopy(data, 0, newBuffer, 0, dataLength);
            data = newBuffer;
        }
        
        System.arraycopy(newData, 0, data, dataLength, length);
        dataLength += length;
    }

    public boolean isHeaderParsed() {
        if (headersParsed) return true;
        if (cachedHeaderEnd < 0) {
            cachedHeaderEnd = findHeaderEnd();
        }
        return cachedHeaderEnd >= 0;
    }

    private int findHeaderEnd() {
        for (int i = 0; i < dataLength - 3; i++) {
            if (data[i] == '\r' && data[i + 1] == '\n' && data[i + 2] == '\r' && data[i + 3] == '\n') {
                return i;
            }
        }
        return -1;
    }

    private int findCRLF(byte[] array, int start, int end) {
        for (int i = start; i < end - 1; i++) {
            if (array[i] == '\r' && array[i + 1] == '\n') {
                return i;
            }
        }
        return -1;
    }

    private int findDoubleCRLF(byte[] array, int start, int end) {
        for (int i = start; i < end - 3; i++) {
            if (array[i] == '\r' && array[i + 1] == '\n' && array[i + 2] == '\r' && array[i + 3] == '\n') {
                return i;
            }
        }
        return -1;
    }

    public boolean hasCompleteRequest(long bodyLimit) throws Exception {
        if (!isHeaderParsed()) {
            return false;
        }

        if (!headersParsed) {
            parseHeaderMetadata(bodyLimit);
            headersParsed = true;
        }

        int bodyStart = cachedHeaderEnd + 4;

        if (isChunked) {
            ChunkedParseResult res = parseChunked(data, bodyStart, dataLength, bodyLimit);
            return res.complete;
        } else {
            return dataLength >= bodyStart + expectedBodyLength;
        }
    }

    private void parseHeaderMetadata(long bodyLimit) throws Exception {
        String headerStr = new String(data, 0, cachedHeaderEnd, StandardCharsets.UTF_8);
        String[] lines = headerStr.split("\r\n");
        expectedBodyLength = 0;
        isChunked = false;

        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].toLowerCase();
            if (line.startsWith("content-length:")) {
                try {
                    expectedBodyLength = Long.parseLong(line.substring(line.indexOf(':') + 1).trim());
                    if (bodyLimit > 0 && expectedBodyLength > bodyLimit) {
                        throw new Exception("413 Payload Too Large");
                    }
                } catch (NumberFormatException e) {
                    throw new Exception("400 Bad Request");
                }
            } else if (line.startsWith("transfer-encoding:") && line.contains("chunked")) {
                isChunked = true;
            }
        }
    }

    public HttpRequest parseRequest(long bodyLimit) throws Exception {
        if (parsedRequest != null) return parsedRequest;

        if (!hasCompleteRequest(bodyLimit)) {
            throw new Exception("Incomplete request");
        }

        String headerStr = new String(data, 0, cachedHeaderEnd, StandardCharsets.UTF_8);
        String[] lines = headerStr.split("\r\n");

        if (lines.length < 1) {
            throw new Exception("400 Bad Request");
        }

        HttpRequest request = new HttpRequest();
        String[] requestLine = lines[0].split(" ", 3);
        if (requestLine.length != 3) {
            throw new Exception("400 Bad Request");
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

        if (isChunked) {
            ChunkedParseResult res = parseChunked(data, bodyStart, dataLength, bodyLimit);
            if (!res.complete) {
                throw new Exception("Incomplete chunked request");
            }
            request.setBody(res.body);
        } else {
            if (expectedBodyLength > 0) {
                byte[] body = new byte[(int) expectedBodyLength];
                System.arraycopy(data, bodyStart, body, 0, (int) expectedBodyLength);
                request.setBody(body);
            }
        }

        parsedRequest = request;
        return request;
    }

    private static class ChunkedParseResult {
        boolean complete;
        byte[] body;
        int totalBytesRead;
    }

    private ChunkedParseResult parseChunked(byte[] data, int bodyStart, int dataLength, long limit) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int p = bodyStart;
        while (true) {
            int nextCrlf = findCRLF(data, p, dataLength);
            if (nextCrlf == -1) {
                ChunkedParseResult res = new ChunkedParseResult();
                res.complete = false;
                return res;
            }
            String hex = new String(data, p, nextCrlf - p, StandardCharsets.UTF_8).trim();
            int semi = hex.indexOf(';');
            if (semi >= 0) {
                hex = hex.substring(0, semi).trim();
            }
            int chunkSize;
            try {
                chunkSize = Integer.parseInt(hex, 16);
            } catch (NumberFormatException e) {
                throw new Exception("400 Bad Request");
            }
            
            if (chunkSize == 0) {
                if (nextCrlf + 2 <= dataLength) {
                    if (nextCrlf + 4 <= dataLength && data[nextCrlf + 2] == '\r' && data[nextCrlf + 3] == '\n') {
                        ChunkedParseResult res = new ChunkedParseResult();
                        res.complete = true;
                        res.body = out.toByteArray();
                        res.totalBytesRead = nextCrlf + 4 - bodyStart;
                        return res;
                    } else if (nextCrlf + 2 == dataLength) {
                        ChunkedParseResult res = new ChunkedParseResult();
                        res.complete = true;
                        res.body = out.toByteArray();
                        res.totalBytesRead = nextCrlf + 2 - bodyStart;
                        return res;
                    } else {
                        int doubleCrlf = findDoubleCRLF(data, nextCrlf + 2, dataLength);
                        if (doubleCrlf != -1) {
                            ChunkedParseResult res = new ChunkedParseResult();
                            res.complete = true;
                            res.body = out.toByteArray();
                            res.totalBytesRead = doubleCrlf + 4 - bodyStart;
                            return res;
                        }
                        ChunkedParseResult res = new ChunkedParseResult();
                        res.complete = false;
                        return res;
                    }
                }
                ChunkedParseResult res = new ChunkedParseResult();
                res.complete = false;
                return res;
            }
            
            int chunkDataStart = nextCrlf + 2;
            if (chunkDataStart + chunkSize + 2 > dataLength) {
                ChunkedParseResult res = new ChunkedParseResult();
                res.complete = false;
                return res;
            }
            
            if (data[chunkDataStart + chunkSize] != '\r' || data[chunkDataStart + chunkSize + 1] != '\n') {
                throw new Exception("400 Bad Request");
            }
            
            out.write(data, chunkDataStart, chunkSize);
            if (limit > 0 && out.size() > limit) {
                throw new Exception("413 Payload Too Large");
            }
            
            p = chunkDataStart + chunkSize + 2;
        }
    }

    public HttpRequest parseHeadersOnly() throws Exception {
        if (!isHeaderParsed()) {
            throw new Exception("Headers not parsed");
        }
        String headerStr = new String(data, 0, cachedHeaderEnd, StandardCharsets.UTF_8);
        String[] lines = headerStr.split("\r\n");

        if (lines.length < 1) {
            throw new Exception("400 Bad Request");
        }

        HttpRequest request = new HttpRequest();
        String[] requestLine = lines[0].split(" ", 3);
        if (requestLine.length != 3) {
            throw new Exception("400 Bad Request");
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
        return request;
    }
}