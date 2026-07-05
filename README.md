# Custom HTTP Server in Java

A lightweight, event-driven HTTP/1.1 server built entirely with Java Core Libraries (Java NIO). No external dependencies required!

## Features

✅ **HTTP/1.1 Compliant**
- Handles GET, POST, DELETE methods
- Proper HTTP status codes and headers
- Cookie support

✅ **Non-Blocking I/O**
- Event-driven architecture using Java NIO Selector
- Handles thousands of concurrent connections with a single thread
- Timeout management for long requests

✅ **Static File Serving**
- Serves static content with proper MIME types
- Directory handling with default files
- Security checks to prevent path traversal attacks

✅ **CGI Support**
- Execute Python 3 and Bash scripts
- Environment variable passing (PATH_INFO, REQUEST_METHOD, etc.)
- Process timeout handling

✅ **File Uploads**
- Handle POST requests with file bodies
- Configurable upload size limits
- DELETE method for file removal

✅ **Configuration**
- JSON-based configuration (custom parser, no external libs)
- Multiple servers on different ports
- Per-route configuration
- Custom error pages

✅ **Error Handling**
- Default error pages for HTTP errors (400, 403, 404, 405, 413, 500)
- Custom error page support
- Graceful degradation

## Project Structure

```
├── src/
│   ├── Main.java                 # Entry point
│   ├── http/
│   │   ├── HttpRequest.java      # HTTP request parsing
│   │   ├── HttpResponse.java     # HTTP response building
│   │   └── HttpParser.java       # Low-level HTTP parsing
│   ├── server/
│   │   ├── Server.java           # NIO-based server
│   │   └── ServerManager.java    # Multi-server management
│   ├── router/
│   │   └── Router.java           # Request routing and handling
│   ├── handlers/
│   │   └── StaticFileHandler.java # Static file serving
│   ├── cgi/
│   │   └── CgiHandler.java       # CGI script execution
│   ├── error/
│   │   └── ErrorHandler.java     # Error handling
│   └── config/
│       ├── ConfigLoader.java     # Configuration loading
│       ├── ServerConfig.java     # Server configuration model
│       ├── RouteConfig.java      # Route configuration model
│       └── SimpleJsonParser.java # Custom JSON parser
├── config.json                    # Server configuration
├── www/                           # Static content
│   └── index.html
├── cgi-bin/                       # CGI scripts
│   ├── hello.py
│   └── info.sh
├── error_pages/                   # Custom error pages
│   ├── 400.html
│   ├── 403.html
│   ├── 404.html
│   ├── 405.html
│   ├── 413.html
│   └── 500.html
└── uploads/                       # Upload directory
```

## Configuration

The server is configured via `config.json`. Example:

```json
{
  "servers": [
    {
      "host": "0.0.0.0",
      "ports": [8080, 8081],
      "server_name": "main-server",
      "default_server": true,
      "client_max_body_size": "10MB",
      "request_timeout_ms": 30000,
      "error_pages": {
        "400": "/error_pages/400.html",
        "404": "/error_pages/404.html",
        "500": "/error_pages/500.html"
      },
      "routes": [
        {
          "path": "/",
          "methods": ["GET"],
          "root": "./www",
          "default_file": "index.html",
          "directory_listing": false
        },
        {
          "path": "/cgi-bin",
          "methods": ["GET", "POST"],
          "root": "./cgi-bin",
          "cgi": {
            ".py": "/usr/bin/python3",
            ".sh": "/bin/bash"
          }
        },
        {
          "path": "/upload",
          "methods": ["GET", "POST", "DELETE"],
          "root": "./uploads",
          "client_max_body_size": "50MB"
        }
      ]
    }
  ]
}
```

### Configuration Options

- **host**: Bind address (0.0.0.0 for all interfaces)
- **ports**: List of ports to listen on
- **server_name**: Server identifier
- **default_server**: Mark as default server
- **client_max_body_size**: Maximum request body size (format: "10MB", "1GB", etc.)
- **request_timeout_ms**: Request timeout in milliseconds
- **error_pages**: Map of HTTP status codes to error page paths
- **routes**: Array of route configurations

### Route Options

- **path**: Request path pattern
- **methods**: Allowed HTTP methods
- **root**: Root directory for file serving
- **default_file**: Default file for directories (e.g., index.html)
- **directory_listing**: Enable/disable directory listings
- **client_max_body_size**: Override body size limit for this route
- **cgi**: Map file extensions to CGI interpreters
- **redirect**: Redirect configuration with status and URL

## Building and Running

### Prerequisites

- Java 11 or higher
- Python 3 (for CGI testing)
- Bash (for shell CGI testing)

### Build

```bash
# Compile all Java source files
javac -d . src/**/*.java

# Or with explicit output directory
javac -d bin src/**/*.java
java -cp bin Main
```

### Run

```bash
# Run with default config.json
java Main

# Run with custom config file
java Main /path/to/config.json
```

The server will output something like:
```
=== Custom HTTP Server ===
Loading configuration from: config.json
Started main-server on port 8080
Started main-server on port 8081
Server started on 0.0.0.0:8080
Server started on 0.0.0.0:8081
```

## Testing

### Test Static Files

```bash
curl http://localhost:8080/
curl http://localhost:8080/index.html
```

### Test CGI Python Script

```bash
curl "http://localhost:8080/cgi-bin/hello.py?param1=value1"
```

### Test CGI Shell Script

```bash
curl http://localhost:8080/cgi-bin/info.sh
```

### Test File Upload

```bash
curl -X POST --data-binary @file.txt http://localhost:8080/upload/
```

### Stress Testing with Siege

```bash
# Install siege: apt-get install siege (Ubuntu/Debian)

# Benchmark the server (100 requests, 10 concurrent users)
siege -c 10 -r 10 -b http://localhost:8080/

# Full stress test (target 99.5% availability)
siege -c 50 -r 100 -b http://localhost:8080/

# Sustained load test for 1 minute
siege -c 100 -t 1M http://localhost:8080/
```

### Memory Leak Testing

Monitor the process while running stress tests:

```bash
# Get Java process PID
jps -l

# Monitor memory usage
jstat -gc -h10 <PID> 1000

# Or use htop
htop -p <PID>
```

## Implementation Details

### HTTP Request Parsing

The server manually parses HTTP requests from raw bytes:
- Reads request line (METHOD URI VERSION)
- Parses headers (Key: Value format)
- Extracts query parameters
- Parses cookie headers
- Handles request body based on Content-Length

### Non-Blocking I/O

Uses Java NIO Selector pattern:
- Single event-driven loop
- Separate read and write phases
- Connection timeout monitoring
- Graceful client disconnection handling

### CGI Execution

- ProcessBuilder for safe process creation
- Environment variable setup (REQUEST_METHOD, PATH_INFO, etc.)
- Output parsing for headers and body
- Process timeout handling (5 seconds default)
- Exit code checking

### File Serving

- Proper MIME type detection
- Directory traversal prevention
- Default file handling for directories
- Efficient binary file reading

### Error Handling

- Custom error pages per HTTP status code
- Fallback to generated error pages
- Request validation
- Graceful shutdown on errors

## Security Considerations

1. **Path Traversal**: All file paths are canonicalized and validated
2. **Request Size Limits**: Configurable maximum body size with enforcement
3. **Timeout Protection**: Long requests are terminated after timeout
4. **Resource Limits**: Single thread/process model prevents resource exhaustion
5. **Input Sanitization**: Proper URL decoding and validation

## Performance Characteristics

- **Concurrency**: Handles multiple concurrent connections with single thread
- **Memory**: Efficient buffer management with reuse
- **Scalability**: Event-driven model scales better than thread-per-connection

## Bonus Features

- Multi-port support
- Multi-interpreter CGI support (Python + Bash)
- Flexible configuration system
- Custom error pages
- Request parameter parsing
- Cookie handling

## Known Limitations

- Single process, single thread (by design)
- No HTTPS support
- No persistent session storage
- Basic routing (exact path matching only)
- No compression support

## Troubleshooting

### "Port already in use"

```bash
# Find process using port 8080
lsof -i :8080

# Kill the process
kill -9 <PID>
```

### "File not found" errors

Ensure:
- Static files are in the `www/` directory
- CGI scripts are in the `cgi-bin/` directory
- Upload directory exists (`mkdir uploads`)
- File paths in config.json are correct

### CGI script not executing

Check:
- Python/Bash is installed and in PATH
- CGI script has execute permission (`chmod +x cgi-bin/script.py`)
- Script has proper shebang line
- Output includes HTTP headers

## References

- [RFC 9112 - HTTP/1.1 Semantics](https://www.rfc-editor.org/rfc/rfc9112.html)
- [Java NIO Tutorial](https://docs.oracle.com/javase/tutorial/essential/io/)
- [CGI Standard](https://www.w3.org/CGI/)
- [ProcessBuilder Documentation](https://docs.oracle.com/javase/10/docs/api/java/lang/ProcessBuilder.html)

## License

Educational use only.
