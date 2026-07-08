package router;

import config.RouteConfig;
import config.ServerConfig;
import http.HttpRequest;
import http.HttpResponse;
import handlers.StaticFileHandler;
import cgi.CgiHandler;
import error.ErrorHandler;
import utils.Session;
import utils.SessionManager;
import utils.Metrics;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public class Router {
    private final List<ServerConfig> serverConfigs;

    public Router(List<ServerConfig> serverConfigs) {
        this.serverConfigs = serverConfigs;
    }

    public HttpResponse route(HttpRequest request, ServerConfig activeServerConfig, Session session) {
        
        String path = request.getPath();
        String method = request.getMethod();

        // 1. Intercept Admin & Metrics endpoints
        if (path.equals("/admin") || path.equals("/metrics")) {
            return serveMetricsDashboard(request, activeServerConfig, session);
        }

        if (path.equals("/admin/invalidate")) {
            String targetId = request.getQueryParam("id");
            if (targetId != null) {
                SessionManager.removeSession(targetId);
            }
            HttpResponse redirect = new HttpResponse();
            redirect.setStatusCode(302);
            redirect.setHeader("location", "/admin");
            return redirect;
        }

        if (path.equals("/admin/destroy")) {
            SessionManager.removeSession(session.getId());
            HttpResponse redirect = new HttpResponse();
            redirect.setStatusCode(302);
            redirect.setHeader("location", "/admin");
            redirect.addCookie("session_id", "", 0);
            return redirect;
        }

        if (path.equals("/admin/set-attr") && method.equals("POST")) {
            String bodyStr = new String(request.getBody(), StandardCharsets.UTF_8);
            String attrKey = null;
            String attrVal = null;
            String[] pairs = bodyStr.split("&");
            for (String pair : pairs) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2) {
                    String k = HttpRequest.urlDecode(kv[0]);
                    String v = HttpRequest.urlDecode(kv[1]);
                    if (k.equals("key")) attrKey = v;
                    if (k.equals("value")) attrVal = v;
                }
            }
            if (attrKey != null && !attrKey.trim().isEmpty() && attrVal != null) {
                session.setAttribute(attrKey, attrVal);
            }
            HttpResponse redirect = new HttpResponse();
            redirect.setStatusCode(302);
            redirect.setHeader("location", "/admin");
            return redirect;
        }

        RouteConfig matchedRoute = findMatchingRoute(activeServerConfig, path);

        if (matchedRoute == null) {
            return ErrorHandler.handleError(activeServerConfig, 404, "Route not found: " + path, request);
        }

        if (!matchedRoute.getMethods().contains(method)) {
            return ErrorHandler.handleError(activeServerConfig, 405, "Method " + method + " not allowed for " + path, request);
        }

        if (matchedRoute.isRedirect()) {
            return handleRedirect(matchedRoute);
        }

        if (matchedRoute.hasCgi()) {
            // Return null to signal to Server.java that this is a CGI request, 
            // which will start the process asynchronously.
            return null;
        }

        long activeMaxBodySize = matchedRoute.getClientMaxBodySize();

        if (activeMaxBodySize > 0 && request.getBody().length > activeMaxBodySize) {
            return ErrorHandler.handleError(activeServerConfig, 413, "Payload too large", request);
        }

        String relativePath = calculateRelativePath(path, matchedRoute.getPath());

        return switch (method) {
            case "GET" -> StaticFileHandler.handleRequest(activeServerConfig, request, matchedRoute.getRoot(), matchedRoute.getDefaultFile(), relativePath, matchedRoute.getDirectoryListing());
            case "POST" -> handlePost(request, matchedRoute, activeServerConfig);
            case "DELETE" -> handleDelete(request, matchedRoute, activeServerConfig);
            case "HEAD" -> {
                HttpResponse response = StaticFileHandler.handleRequest(activeServerConfig, request, matchedRoute.getRoot(), matchedRoute.getDefaultFile(), relativePath, matchedRoute.getDirectoryListing());
                response.setBody(new byte[0]);
                yield response;
            }
            default -> ErrorHandler.handleError(activeServerConfig, 501, "Method not implemented", request);
        };
    }

    public ServerConfig resolveVirtualHost(HttpRequest request) {
        String hostHeader = request.getHeader("host");
        
        if (hostHeader != null) {
            String requestedHost = hostHeader.split(":")[0];
            for (ServerConfig config : serverConfigs) {
                if (config.getServerName() != null && config.getServerName().equals(requestedHost)) {
                    return config;
                }
            }
        }

        for (ServerConfig config : serverConfigs) {
            if (config.getDefaultServer() != null && config.getDefaultServer()) {
                return config;
            }
        }

        return serverConfigs.isEmpty() ? null : serverConfigs.get(0);
    }

    public RouteConfig findMatchingRoute(ServerConfig config, String path) {
        RouteConfig bestMatch = null;
        int bestMatchLength = -1;

        List<RouteConfig> routes = config.getRoutes();
        if (routes == null) return null;

        for (RouteConfig route : routes) {
            String routePath = route.getPath();
            
            if (path.equals(routePath)) {
                return route;
            }
            
            if (path.startsWith(routePath + "/") || (routePath.equals("/") && path.startsWith("/"))) {
                if (routePath.length() > bestMatchLength) {
                    bestMatch = route;
                    bestMatchLength = routePath.length();
                }
            }
        }

        return bestMatch;
    }

    private String calculateRelativePath(String requestPath, String routePath) {
        if (requestPath.equals(routePath)) {
            return "";
        }
        if (routePath.equals("/")) {
            return requestPath.startsWith("/") ? requestPath.substring(1) : requestPath;
        }
        if (requestPath.startsWith(routePath + "/")) {
            return requestPath.substring(routePath.length() + 1);
        }
        return "";
    }

    private HttpResponse handlePost(HttpRequest request, RouteConfig route, ServerConfig config) {
        HttpResponse response = new HttpResponse();

        byte[] body = request.getBody();
        if (body.length == 0) {
            return ErrorHandler.handleError(config, 400, "Empty request body", request);
        }

        String origFilename = extractFilename(request.getHeader("content-disposition"));
        String extension = "";
        if (origFilename != null) {
            int lastDot = origFilename.lastIndexOf('.');
            if (lastDot >= 0) {
                extension = origFilename.substring(lastDot);
            }
        }
        String filename = java.util.UUID.randomUUID().toString() + extension;

        String uploadPath = route.getRoot() + "/" + filename;
        try {
            java.nio.file.Files.createDirectories(java.nio.file.Paths.get(route.getRoot()));
            java.nio.file.Files.write(java.nio.file.Paths.get(uploadPath), body);
            response.setStatusCode(201);
            response.setBody("File uploaded successfully: " + filename);
            response.setHeader("content-type", "text/plain");
        } catch (Exception e) {
            return ErrorHandler.handleError(config, 500, "Upload failed", request);
        }

        return response;
    }

    private HttpResponse handleDelete(HttpRequest request, RouteConfig route, ServerConfig config) {
        HttpResponse response = new HttpResponse();

        String path = request.getPath();
        if (path.startsWith(route.getPath())) {
            path = path.substring(route.getPath().length());
        }

        if (path.startsWith("/")) {
            path = path.substring(1);
        }

        String filePath = route.getRoot() + "/" + path;
        try {
            java.nio.file.Files.delete(java.nio.file.Paths.get(filePath));
            response.setStatusCode(204);
            response.setBody(new byte[0]);
        } catch (Exception e) {
            return ErrorHandler.handleError(config, 404, "File not found", request);
        }

        return response;
    }

    private HttpResponse handleRedirect(RouteConfig route) {
        HttpResponse response = new HttpResponse();
        Map<String, Object> redirect = route.getRedirect();

        Number statusNum = (Number) redirect.get("status");
        String url = (String) redirect.get("url");

        if (statusNum != null) {
            response.setStatusCode(statusNum.intValue());
        }

        if (url != null) {
            response.setHeader("location", url);
        }

        return response;
    }

    private String extractFilename(String contentDisposition) {
        if (contentDisposition == null) {
            return null;
        }

        String[] parts = contentDisposition.split(";");
        for (String part : parts) {
            part = part.trim();
            if (part.startsWith("filename=")) {
                return part.substring(9).replace("\"", "");
            }
        }

        return null;
    }

    private HttpResponse serveMetricsDashboard(HttpRequest request, ServerConfig serverConfig, Session session) {
        String format = request.getQueryParam("format");
        if ("json".equals(format) || (request.getHeader("accept") != null && request.getHeader("accept").contains("application/json"))) {
            HttpResponse response = new HttpResponse();
            response.setStatusCode(200);
            response.setHeader("content-type", "application/json");
            
            StringBuilder json = new StringBuilder("{");
            json.append("\"uptime_ms\":").append(Metrics.getUptimeMs()).append(",");
            json.append("\"total_requests\":").append(Metrics.getTotalRequests()).append(",");
            json.append("\"active_connections\":").append(Metrics.getActiveConnections()).append(",");
            
            json.append("\"status_codes\":{");
            boolean first = true;
            for (Map.Entry<Integer, Long> entry : Metrics.getStatusCodeCounts().entrySet()) {
                if (!first) json.append(",");
                json.append("\"").append(entry.getKey()).append("\":").append(entry.getValue());
                first = false;
            }
            json.append("},");
            
            json.append("\"active_sessions\":").append(SessionManager.getActiveSessionsCount());
            json.append("}");
            
            response.setBody(json.toString());
            return response;
        }

        HttpResponse response = new HttpResponse();
        response.setStatusCode(200);
        response.setHeader("content-type", "text/html");

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><title>localserver Admin Dashboard</title>");
        html.append("<link href=\"https://fonts.googleapis.com/css2?family=Outfit:wght@300;400;600;700&display=swap\" rel=\"stylesheet\">");
        html.append("<style>");
        html.append("body { font-family: 'Outfit', sans-serif; background-color: #0f172a; color: #e2e8f0; margin: 0; padding: 0; } ");
        html.append(".container { max-width: 1200px; margin: 0 auto; padding: 40px 20px; } ");
        html.append("header { display: flex; justify-content: space-between; align-items: center; border-bottom: 1px solid #334155; padding-bottom: 20px; margin-bottom: 30px; } ");
        html.append("h1 { font-size: 2.5rem; font-weight: 700; background: linear-gradient(135deg, #38bdf8, #818cf8); -webkit-background-clip: text; -webkit-text-fill-color: transparent; margin: 0; } ");
        html.append(".badge { background-color: #06b6d4; color: #0f172a; padding: 6px 12px; border-radius: 9999px; font-weight: 600; font-size: 0.875rem; } ");
        html.append(".grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); gap: 20px; margin-bottom: 30px; } ");
        html.append(".card { background: #1e293b; border: 1px solid #334155; border-radius: 16px; padding: 24px; box-shadow: 0 4px 6px -1px rgba(0,0,0,0.1); transition: transform 0.2s, border-color 0.2s; } ");
        html.append(".card:hover { transform: translateY(-4px); border-color: #475569; } ");
        html.append(".card h3 { margin: 0; color: #94a3b8; font-size: 0.875rem; text-transform: uppercase; letter-spacing: 0.05em; } ");
        html.append(".card-val { font-size: 2.25rem; font-weight: 700; color: #f8fafc; margin-top: 10px; } ");
        html.append(".btn { background: linear-gradient(135deg, #0284c7, #4f46e5); color: white; border: none; padding: 10px 20px; border-radius: 8px; font-weight: 600; cursor: pointer; transition: opacity 0.2s; text-decoration: none; display: inline-block; } ");
        html.append(".btn:hover { opacity: 0.9; } ");
        html.append(".btn-danger { background: linear-gradient(135deg, #dc2626, #b91c1c); } ");
        html.append("table { width: 100%; border-collapse: collapse; margin-top: 15px; } ");
        html.append("th, td { padding: 12px 16px; text-align: left; border-bottom: 1px solid #334155; } ");
        html.append("th { color: #94a3b8; font-weight: 600; } ");
        html.append("tr:hover { background-color: #1e293b; } ");
        html.append(".session-section { background: #1e293b; border: 1px solid #334155; border-radius: 16px; padding: 30px; margin-bottom: 30px; } ");
        html.append(".session-section h2 { margin: 0 0 20px 0; font-size: 1.5rem; border-bottom: 1px solid #334155; padding-bottom: 10px; } ");
        html.append(".form-group { margin-bottom: 15px; } ");
        html.append("label { display: block; margin-bottom: 5px; color: #94a3b8; } ");
        html.append("input[type=\"text\"] { background-color: #0f172a; border: 1px solid #334155; color: #e2e8f0; padding: 10px; border-radius: 6px; width: 250px; } ");
        html.append("</style></head><body>");

        html.append("<div class=\"container\">");
        html.append("<header><div><h1>localserver Metrics Dashboard</h1><p style=\"margin: 5px 0 0 0; color: #94a3b8;\">Real-time web server telemetry</p></div>");
        html.append("<span class=\"badge\">Active</span></header>");

        // Stats grid
        long uptimeSec = Metrics.getUptimeMs() / 1000;
        long days = uptimeSec / 86400;
        long hours = (uptimeSec % 86400) / 3600;
        long minutes = (uptimeSec % 3600) / 60;
        long seconds = uptimeSec % 60;
        String uptimeStr = String.format("%dd %dh %dm %ds", days, hours, minutes, seconds);

        html.append("<div class=\"grid\">");
        html.append("<div class=\"card\"><h3>Uptime</h3><div class=\"card-val\">").append(uptimeStr).append("</div></div>");
        html.append("<div class=\"card\"><h3>Total Requests</h3><div class=\"card-val\">").append(Metrics.getTotalRequests()).append("</div></div>");
        html.append("<div class=\"card\"><h3>Active Connections</h3><div class=\"card-val\">").append(Metrics.getActiveConnections()).append("</div></div>");
        html.append("<div class=\"card\"><h3>Active Sessions</h3><div class=\"card-val\">").append(SessionManager.getActiveSessionsCount()).append("</div></div>");
        html.append("</div>");

        // Status code counts
        html.append("<div class=\"session-section\"><h2>HTTP Status Distribution</h2>");
        if (Metrics.getStatusCodeCounts().isEmpty()) {
            html.append("<p style=\"color: #94a3b8;\">No requests processed yet.</p>");
        } else {
            html.append("<table><thead><tr><th>Status Code</th><th>Count</th></tr></thead><tbody>");
            for (Map.Entry<Integer, Long> entry : Metrics.getStatusCodeCounts().entrySet()) {
                html.append("<tr><td><strong>").append(entry.getKey()).append("</strong></td><td>").append(entry.getValue()).append("</td></tr>");
            }
            html.append("</tbody></table>");
        }
        html.append("</div>");

        // Current Session Details
        html.append("<div class=\"session-section\"><h2>Your Session (Cookie-based)</h2>");
        html.append("<p><strong>Session ID:</strong> <code style=\"background-color:#0f172a; padding: 4px 8px; border-radius:4px;\">").append(session.getId()).append("</code></p>");
        html.append("<p><strong>Page Visits in Session:</strong> ").append(session.getAttribute("visits")).append("</p>");
        html.append("<p><strong>Session Attributes:</strong></p>");
        
        Map<String, Object> attrs = session.getAttributes();
        if (attrs.size() <= 1) { // Only visits
            html.append("<p style=\"color: #94a3b8;\">No custom attributes set.</p>");
        } else {
            html.append("<ul>");
            for (Map.Entry<String, Object> attr : attrs.entrySet()) {
                if (!attr.getKey().equals("visits")) {
                    html.append("<li><strong>").append(attr.getKey()).append("</strong>: ").append(attr.getValue()).append("</li>");
                }
            }
            html.append("</ul>");
        }

        // Set Attribute Form & Destroy Session Button
        html.append("<div style=\"display: flex; gap: 20px; align-items: flex-end; margin-top: 20px;\">");
        html.append("<form action=\"/admin/set-attr\" method=\"POST\" style=\"display:flex; gap:10px; align-items:flex-end;\">");
        html.append("<div class=\"form-group\" style=\"margin:0;\"><label>Key</label><input type=\"text\" name=\"key\" required placeholder=\"e.g. username\"></div>");
        html.append("<div class=\"form-group\" style=\"margin:0;\"><label>Value</label><input type=\"text\" name=\"value\" required placeholder=\"e.g. Alice\"></div>");
        html.append("<button type=\"submit\" class=\"btn\">Set Attribute</button>");
        html.append("</form>");
        html.append("<a href=\"/admin/destroy\" class=\"btn btn-danger\">Destroy Session</a>");
        html.append("</div>");
        html.append("</div>");

        // Active Sessions List
        html.append("<div class=\"session-section\"><h2>All Active Sessions</h2>");
        Map<String, Session> activeSessions = SessionManager.getActiveSessions();
        if (activeSessions.isEmpty()) {
            html.append("<p style=\"color: #94a3b8;\">No active sessions.</p>");
        } else {
            html.append("<table><thead><tr><th>Session ID</th><th>Created At</th><th>Last Access</th><th>Actions</th></tr></thead><tbody>");
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            for (Session s : activeSessions.values()) {
                html.append("<tr>");
                html.append("<td><code>").append(s.getId()).append("</code>").append(s.getId().equals(session.getId()) ? " <strong>(You)</strong>" : "").append("</td>");
                html.append("<td>").append(sdf.format(new java.util.Date(s.getCreationTime()))).append("</td>");
                html.append("<td>").append(sdf.format(new java.util.Date(s.getLastAccessedTime()))).append("</td>");
                html.append("<td><a href=\"/admin/invalidate?id=").append(s.getId()).append("\" class=\"btn btn-danger\" style=\"padding:4px 8px; font-size:0.75rem;\">Invalidate</a></td>");
                html.append("</tr>");
            }
            html.append("</tbody></table>");
        }
        html.append("</div>");

        html.append("</div></body></html>");
        response.setBody(html.toString());
        return response;
    }
}