package http;

import java.util.*;

public class HttpRequest {
    private String method;
    private String uri;
    private String httpVersion;
    private Map<String, String> headers;
    private Map<String, String> cookies;
    private byte[] body;
    private String queryString;
    private Map<String, String> queryParams;

    public HttpRequest() {
        this.headers = new HashMap<>();
        this.cookies = new HashMap<>();
        this.queryParams = new HashMap<>();
        this.body = new byte[0];
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public String getUri() {
        return uri;
    }

    public void setUri(String uri) {
        this.uri = uri;
        parseQueryString();
    }

    public String getPath() {
        if (uri == null) return "/";
        int questionIndex = uri.indexOf('?');
        return questionIndex > 0 ? uri.substring(0, questionIndex) : uri;
    }

    public String getHttpVersion() {
        return httpVersion;
    }

    public void setHttpVersion(String httpVersion) {
        this.httpVersion = httpVersion;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public String getHeader(String name) {
        return headers.get(name.toLowerCase());
    }

    public void addHeader(String name, String value) {
        headers.put(name.toLowerCase(), value);
    }

    public Map<String, String> getCookies() {
        return cookies;
    }

    public String getCookie(String name) {
        return cookies.get(name);
    }

    public void parseCookies() {
        String cookieHeader = getHeader("cookie");
        if (cookieHeader != null) {
            String[] cookiePairs = cookieHeader.split(";");
            for (String pair : cookiePairs) {
                String[] kv = pair.trim().split("=", 2);
                if (kv.length == 2) {
                    cookies.put(kv[0], kv[1]);
                }
            }
        }
    }

    public byte[] getBody() {
        return body;
    }

    public void setBody(byte[] body) {
        this.body = body != null ? body : new byte[0];
    }

    public Map<String, String> getQueryParams() {
        return queryParams;
    }

    public String getQueryParam(String name) {
        return queryParams.get(name);
    }

    private void parseQueryString() {
        queryParams.clear();
        if (uri == null) return;
        
        int questionIndex = uri.indexOf('?');
        if (questionIndex > 0) {
            queryString = uri.substring(questionIndex + 1);
            String[] pairs = queryString.split("&");
            for (String pair : pairs) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2) {
                    queryParams.put(urlDecode(kv[0]), urlDecode(kv[1]));
                } else if (kv.length == 1) {
                    queryParams.put(urlDecode(kv[0]), "");
                }
            }
        }
    }

    public long getContentLength() {
        String contentLength = getHeader("content-length");
        if (contentLength != null) {
            try {
                return Long.parseLong(contentLength);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    public String getContentType() {
        return getHeader("content-type");
    }

    public boolean isChunked() {
        String transferEncoding = getHeader("transfer-encoding");
        return transferEncoding != null && transferEncoding.toLowerCase().contains("chunked");
    }

    public static String urlDecode(String s) {
        if (s == null) return "";
        try {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '+') {
                    sb.append(' ');
                } else if (c == '%' && i + 2 < s.length()) {
                    String hex = s.substring(i + 1, i + 3);
                    sb.append((char) Integer.parseInt(hex, 16));
                    i += 2;
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return s;
        }
    }

    @Override
    public String toString() {
        return method + " " + uri + " " + httpVersion;
    }
}
