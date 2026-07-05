#!/bin/bash

# Simple CGI shell script for testing

echo "Status: 200 OK"
echo "Content-Type: text/html"
echo ""
echo "<html>"
echo "<head><title>Shell CGI Test</title></head>"
echo "<body>"
echo "<h1>Shell CGI Script Output</h1>"
echo "<p>Request Method: $REQUEST_METHOD</p>"
echo "<p>Path Info: $PATH_INFO</p>"
echo "<p>Query String: $QUERY_STRING</p>"
echo "<p>Server Name: $SERVER_NAME</p>"
echo "<p>Server Port: $SERVER_PORT</p>"
echo "</body>"
echo "</html>"
