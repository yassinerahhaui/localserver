#!/bin/bash

# Build script for the HTTP Server

echo "=== Building Custom HTTP Server ==="
echo ""

# Create bin directory
mkdir -p bin

echo "Compiling Java source files..."
find src -name "*.java" | xargs javac -d bin

if [ $? -eq 0 ]; then
    echo "✓ Compilation successful!"
    echo ""
    echo "To run the server:"
    echo "  java -cp bin Main"
    echo ""
    echo "Or with custom config:"
    echo "  java -cp bin Main /path/to/config.json"
else
    echo "✗ Compilation failed!"
    exit 1
fi
