# LocalServer



### Project Structure

```
    java-server/
    │
    ├── src/
    │   ├── Main.java                    # Entry point — loads config, starts servers
    │   │
    │   ├── config/
    │   │   ├── ConfigLoader.java        # Reads & parses config.json
    │   │   ├── ServerConfig.java        # Model: one server block
    │   │   └── RouteConfig.java         # Model: one route block
    │   │
    │   ├── server/
    │   │   ├── Server.java              # Opens ServerSocketChannel, runs Selector loop
    │   │   ├── ClientHandler.java       # Reads raw bytes, writes response per client
    │   │   └── TimeoutManager.java      # Tracks & kills stale connections
    │   │
    │   ├── http/
    │   │   ├── HttpRequest.java         # Parsed request object (method, path, headers, body)
    │   │   ├── HttpResponse.java        # Builds raw HTTP response bytes
    │   │   ├── HttpParser.java          # Turns raw bytes → HttpRequest
    │   │   ├── HttpMethod.java          # Enum: GET, POST, DELETE
    │   │   └── HttpStatus.java          # Enum: 200, 301, 400, 403, 404, 405, 413, 500
    │   │
    │   ├── router/
    │   │   ├── Router.java              # Matches request path → RouteConfig
    │   │   └── StaticFileHandler.java   # Serves files from disk, directory listing
    │   │
    │   ├── handlers/
    │   │   ├── GetHandler.java          # Handles GET logic
    │   │   ├── PostHandler.java         # Handles POST + file upload (multipart)
    │   │   ├── DeleteHandler.java       # Handles DELETE — removes file from disk
    │   │   └── RedirectHandler.java     # Returns 301/302 with Location header
    │   │
    │   ├── cgi/
    │   │   └── CgiHandler.java          # Runs scripts via ProcessBuilder, streams output
    │   │
    │   ├── session/
    │   │   ├── SessionManager.java      # HashMap of sessionId → data, expiry logic
    │   │   └── CookieParser.java        # Parses Cookie header, builds Set-Cookie
    │   │
    │   └── error/
    │       └── ErrorHandler.java        # Returns correct error page or default HTML
    │
    ├── www/                             # Static files root
    │   └── index.html
    │
    ├── uploads/                         # Uploaded files go here
    │
    ├── cgi-bin/                         # CGI scripts
    │   ├── hello.py
    │   └── info.sh
    │
    ├── error_pages/                     # Custom error HTML files
    │   ├── 400.html
    │   ├── 403.html
    │   ├── 404.html
    │   ├── 405.html
    │   ├── 413.html
    │   └── 500.html
    │
    ├── config.json                      # Server configuration
    └── README.md
```