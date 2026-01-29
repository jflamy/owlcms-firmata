#!/bin/bash -
# Sync device configuration .xlsx files from diagrams/ to src/main/resources/devices/

echo "Syncing device configuration files..."

# Create target directory if it doesn't exist
mkdir -p ../src/main/resources/devices

# Remove old files
rm -f ../src/main/resources/devices/*.xlsx

# Copy current files from diagrams
find ../diagrams -name '*.xlsx' -exec cp {} ../src/main/resources/devices/ \;

echo "Synced $(ls -1 ../src/main/resources/devices/*.xlsx 2>/dev/null | wc -l) files to src/main/resources/devices/"
ls -1 ../src/main/resources/devices/
