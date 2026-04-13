#!/bin/bash

# --- CONFIGURATION ---
R2_ENDPOINT="https://<ACCOUNT_ID>.r2.cloudflarestorage.com"
BUCKET_NAME="maps"
ACCESS_KEY="ACCESS_KEY_HERE"
SECRET_KEY="SECRET_KEY_HERE"
DEFAULT_PBF="planet-latest.osm.pbf"
DATA_DIR="map_data"

# Helper to run commands with sudo only if available
run_cmd() {
    if command -v sudo >/dev/null 2>&1; then
        sudo "$@"
    else
        "$@"
    fi
}

# Use provided argument or default to planet-latest.osm.pbf
INPUT_PBF="${1:-$DEFAULT_PBF}"

# --- 1. SYSTEM DEPENDENCIES ---
echo "[1/6] Installing system dependencies..."
run_cmd apt-get update
run_cmd apt-get install -y \
    build-essential libosmium2-dev libsqlite3-dev \
    libprotozero-dev libexpat1-dev zlib1g-dev libbz2-dev \
    curl unzip wget

# --- 2. AWS CLI INSTALLATION ---
if ! command -v aws &> /dev/null; then
    echo "[2/6] Installing AWS CLI v2..."
    curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "awscliv2.zip"
    unzip -q awscliv2.zip
    run_cmd ./aws/install
    rm -rf aws awscliv2.zip
else
    echo "[2/6] AWS CLI is already installed."
fi

# --- 3. DATA DOWNLOAD ---
if [ ! -f "$INPUT_PBF" ]; then
    echo "[3/6] $INPUT_PBF not found. Downloading latest planet file..."
    curl -OL "https://planet.openstreetmap.org/pbf/planet-latest.osm.pbf"
else
    echo "[3/6] $INPUT_PBF already exists. Skipping download."
fi

# --- 4. CLOUDFLARE R2 CONFIGURATION ---
echo "[4/6] Configuring credentials for R2..."
aws configure set aws_access_key_id "$ACCESS_KEY" --profile r2
aws configure set aws_secret_access_key "$SECRET_KEY" --profile r2
aws configure set region auto --profile r2

# --- 5. COMPILATION & EXECUTION ---
if [ ! -f "generator.cpp" ]; then
    echo "Error: generator.cpp not found in $(pwd)!"
    exit 1
fi

echo "[5/6] Compiling and running generator..."
g++ -O3 -std=c++17 generator.cpp -o generator -lsqlite3 -lexpat -lz -lbz2 -pthread

if [ $? -eq 0 ]; then
    echo "Preparing output directory: $DATA_DIR..."
    mkdir -p "$DATA_DIR"
    echo "Starting map generation for $INPUT_PBF..."
    ./generator "$INPUT_PBF"
else
    echo "Compilation failed!"
    exit 1
fi

# --- 6. AUTOMATED UPLOAD ---
echo "[6/6] Uploading files to Cloudflare R2..."

# 6a. Upload Global Files
echo "Syncing data directory to R2..."

aws s3 sync "$DATA_DIR" "s3://$BUCKET_NAME/" \
    --endpoint-url "$R2_ENDPOINT" \
    --profile r2 \
    --content-type "application/octet-stream" \
    --exclude "*" \
    --include "road_names.bin" \
    --include "metadata.bin" \
    --include "amenities.db" \
    --include "nodes_zone_*.bin" \
    --include "edges_zone_*.bin"

echo "---------------------------------------------------------"
echo "Pipeline Finished Successfully."
echo "---------------------------------------------------------"