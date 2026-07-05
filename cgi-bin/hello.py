#!/usr/bin/env python3
import os
import sys
import json
from datetime import datetime

# This is a simple CGI script for testing

def get_server_info():
    info = {
        "timestamp": datetime.now().isoformat(),
        "server_name": os.environ.get("SERVER_NAME", "unknown"),
        "server_port": os.environ.get("SERVER_PORT", "unknown"),
        "request_method": os.environ.get("REQUEST_METHOD", "GET"),
        "path_info": os.environ.get("PATH_INFO", "/"),
        "query_string": os.environ.get("QUERY_STRING", ""),
        "content_length": os.environ.get("CONTENT_LENGTH", "0"),
    }
    return info

def main():
    # Print CGI response headers
    print("Status: 200 OK")
    print("Content-Type: application/json")
    print()
    
    # Get server info
    info = get_server_info()
    
    # Print JSON response
    print(json.dumps(info, indent=2))

if __name__ == "__main__":
    main()
