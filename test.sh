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
