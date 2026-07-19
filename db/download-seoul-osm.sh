#!/usr/bin/env bash
set -euo pipefail

mkdir -p osm-data

if [ ! -f osm-data/south-korea-latest.osm.pbf ]; then
  echo "Downloading South Korea OSM extract..."
  curl -L -o osm-data/south-korea-latest.osm.pbf \
    https://download.geofabrik.de/asia/south-korea-latest.osm.pbf
fi

echo "Clipping to Seoul bounding box..."
MSYS_NO_PATHCONV=1 docker run --rm -v "$(pwd)/osm-data:/data" iboates/osmium:latest \
  extract --bbox=126.76,37.42,127.18,37.70 \
  -o /data/seoul.osm.pbf --overwrite \
  /data/south-korea-latest.osm.pbf

echo "Done: osm-data/seoul.osm.pbf"
ls -lh osm-data/seoul.osm.pbf
