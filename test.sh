#!/bin/bash

# Test script for the HTTP Server

echo "=== HTTP Server Test Suite ==="
echo ""

# Start the server in the background
cd "$(dirname "$0")" || exit 1

java -cp bin Main > /tmp/server.log 2>&1 &
SERVER_PID=$!
trap "kill $SERVER_PID 2>/dev/null" EXIT

# Wait for server to start and be ready
sleep 3

# Verify server is running
for i in {1..5}; do
    if curl -s http://localhost:8080/ > /dev/null 2>&1; then
        break
    fi
    sleep 1
done

PORT=8080

# Color codes
GREEN='\033[0;32m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# Test counter
PASS=0
FAIL=0

test_endpoint() {
    local name=$1
    local method=${2:-GET}
    local url=$3
    local expected_status=${4:-200}
    local data=${5:-}

    echo -n "Testing $name... "
    
    if [ -z "$data" ]; then
        response=$(curl -s -w "\n%{http_code}" -X $method "http://localhost:$PORT$url")
    else
        response=$(curl -s -w "\n%{http_code}" -X $method -d "$data" -H "Content-Type: text/plain" "http://localhost:$PORT$url")
    fi
    
    status=$(echo "$response" | tail -n1)
    
    if [ "$status" = "$expected_status" ]; then
        echo -e "${GREEN}✓ PASS${NC} (HTTP $status)"
        ((PASS++))
    else
        echo -e "${RED}✗ FAIL${NC} (Expected $expected_status, got $status)"
        ((FAIL++))
    fi
}

# Test cases
echo "--- Static Files ---"
test_endpoint "GET /" "GET" "/" 200
test_endpoint "GET /index.html" "GET" "/index.html" 200
test_endpoint "GET /nonexistent.html" "GET" "/nonexistent.html" 404

echo ""
echo "--- CGI Scripts ---"
test_endpoint "GET /cgi-bin/hello.py" "GET" "/cgi-bin/hello.py" 200
test_endpoint "GET /cgi-bin/info.sh" "GET" "/cgi-bin/info.sh" 200

echo ""
echo "--- HTTP Methods ---"
test_endpoint "POST /upload" "POST" "/upload" 201 "test upload data"
test_endpoint "GET /upload" "GET" "/upload" 200

echo ""
echo "--- Error Pages ---"
test_endpoint "GET /nonexistent-path" "GET" "/nonexistent-path" 404
test_endpoint "Invalid method" "OPTIONS" "/" 405

echo ""
echo "--- Redirects ---"
test_endpoint "GET /old-api" "GET" "/old-api" 301

echo ""
echo "--- Secondary Server (Port 9090) ---"
response_9090=$(curl -s -w "\n%{http_code}" "http://localhost:9090/")
status_9090=$(echo "$response_9090" | tail -n1)
if [ "$status_9090" = "200" ]; then
    echo -e "${GREEN}✓ PASS${NC} (GET http://localhost:9090/ - HTTP 200)"
    ((PASS++))
else
    echo -e "${RED}✗ FAIL${NC} (GET http://localhost:9090/ - Expected 200, got $status_9090)"
    ((FAIL++))
fi

response_upload=$(curl -s -w "\n%{http_code}" -X POST -H "Content-Disposition: attachment; filename=\"test9090.txt\"" -d "hello 9090" "http://localhost:9090/upload")
status_upload=$(echo "$response_upload" | tail -n1)
upload_body=$(echo "$response_upload" | head -n -1)
uploaded_filename=$(echo "$upload_body" | sed 's/File uploaded successfully: //')

if [ "$status_upload" = "201" ]; then
    echo -e "${GREEN}✓ PASS${NC} (POST http://localhost:9090/upload - HTTP 201)"
    ((PASS++))
else
    echo -e "${RED}✗ FAIL${NC} (POST http://localhost:9090/upload - Expected 201, got $status_upload)"
    ((FAIL++))
fi

response_list=$(curl -s -w "\n%{http_code}" -H "Accept: application/json" "http://localhost:9090/upload")
status_list=$(echo "$response_list" | tail -n1)
content_list=$(echo "$response_list" | head -n -1)
if [ "$status_list" = "200" ] && [[ "$content_list" == *"$uploaded_filename"* ]]; then
    echo -e "${GREEN}✓ PASS${NC} (GET http://localhost:9090/upload [JSON] - HTTP 200)"
    ((PASS++))
else
    echo -e "${RED}✗ FAIL${NC} (GET http://localhost:9090/upload [JSON] - Expected 200 containing $uploaded_filename, got $status_list)"
    ((FAIL++))
fi

response_delete=$(curl -s -w "\n%{http_code}" -X DELETE "http://localhost:9090/upload/$uploaded_filename")
status_delete=$(echo "$response_delete" | tail -n1)
if [ "$status_delete" = "204" ]; then
    echo -e "${GREEN}✓ PASS${NC} (DELETE http://localhost:9090/upload/$uploaded_filename - HTTP 204)"
    ((PASS++))
else
    echo -e "${RED}✗ FAIL${NC} (DELETE http://localhost:9090/upload/$uploaded_filename - Expected 204, got $status_delete)"
    ((FAIL++))
fi

echo ""
echo "--- Summary ---"
echo -e "${GREEN}Passed: $PASS${NC}"
echo -e "${RED}Failed: $FAIL${NC}"

if [ $FAIL -eq 0 ]; then
    echo -e "${GREEN}All tests passed!${NC}"
    exit 0
else
    echo -e "${RED}Some tests failed.${NC}"
    exit 1
fi
