#!/bin/sh
# Imports elevation for one region. Usage: ./import-region.sh minLat maxLat minLon maxLon
# Example (Europe): ./import-region.sh 35 72 -12 45
set -e
if [ $# -lt 4 ]; then
  echo "Usage: $0 minLat maxLat minLon maxLon"
  echo "Example: $0 35 72 -12 45"
  exit 2
fi
DIR=$(cd "$(dirname "$0")" && pwd)
java -Xmx1G -jar "$DIR/realearth-importer.jar" "$DIR/../config/realearth" \
     --bbox "$1,$2,$3,$4" --only elevation
