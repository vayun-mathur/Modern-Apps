#!/bin/bash

# Configuration
SOURCE_URL="https://demo-bucket.protomaps.com/v4.pmtiles"
OUTPUT_DIR="./zones"
mkdir -p "$OUTPUT_DIR"

# R2 Configuration
# Note: Ensure your environment variables (AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY) are set
R2_ENDPOINT="https://<ACCOUNT_ID>.r2.cloudflarestorage.com"
R2_BUCKET="maps-bucket"

# --- Automatic go-pmtiles Installation ---
if ! command -v pmtiles &> /dev/null; then
    echo "pmtiles CLI not found. Attempting to install..."

    # Detect OS and Architecture
    OS=$(uname -s | tr '[:upper:]' '[:lower:]')
    ARCH=$(uname -m)
    case $ARCH in
        x86_64) ARCH="x86_64" ;;
        aarch64|arm64) ARCH="arm64" ;;
        *) echo "Unsupported architecture: $ARCH"; exit 1 ;;
    esac

    # Get latest version tag from GitHub
    VERSION=$(curl -s https://api.github.com/repos/protomaps/go-pmtiles/releases/latest | grep '"tag_name":' | sed -E 's/.*"([^"]+)".*/\1/' | sed 's/v//')

    DOWNLOAD_URL="https://github.com/protomaps/go-pmtiles/releases/download/v${VERSION}/go-pmtiles_${VERSION}_${OS}_${ARCH}.tar.gz"

    echo "Downloading pmtiles v${VERSION} for ${OS}_${ARCH}..."
    curl -L "$DOWNLOAD_URL" -o pmtiles.tar.gz

    tar -xzf pmtiles.tar.gz pmtiles
    chmod +x pmtiles

    # Try to move to a path directory, fallback to local directory
    if [ -w /usr/local/bin ]; then
        sudo mv pmtiles /usr/local/bin/
        echo "Installed to /usr/local/bin/pmtiles"
    else
        echo "Warning: /usr/local/bin not writable. Keeping pmtiles in current directory."
        export PATH=$PATH:$(pwd)
    fi
    rm pmtiles.tar.gz
fi

# Check for other dependencies
if ! command -v aws &> /dev/null; then
    echo "Error: 'aws' CLI not found. Required for R2 syncing."
    exit 1
fi

if ! command -v bc &> /dev/null; then
    echo "Error: 'bc' (calculator) not found. Please install it (sudo apt install bc)."
    exit 1
fi

echo "Starting sequential extraction and upload of 64 zones..."

for i in {0..63}; do
    # 1. De-interleave Morton bits (Z-order curve)
    X=0
    [[ $((i & 1)) -ne 0 ]]  && X=$((X | 1))
    [[ $((i & 4)) -ne 0 ]]  && X=$((X | 2))
    [[ $((i & 16)) -ne 0 ]] && X=$((X | 4))

    Y=0
    [[ $((i & 2)) -ne 0 ]]  && Y=$((Y | 1))
    [[ $((i & 8)) -ne 0 ]]  && Y=$((Y | 2))
    [[ $((i & 32)) -ne 0 ]] && Y=$((Y | 4))

    # 2. Calculate Bounding Box
    LEFT=$(echo "-180 + ($X * 45)" | bc -l)
    RIGHT=$(echo "$LEFT + 45" | bc -l)
    BOTTOM=$(echo "-90 + ($Y * 22.5)" | bc -l)
    TOP=$(echo "$BOTTOM + 22.5" | bc -l)

    FILE_NAME="zone_$i.pmtiles"
    LOCAL_PATH="$OUTPUT_DIR/$FILE_NAME"

    echo "--------------------------------------------------------"
    echo "Processing Zone $i (Grid X:$X, Y:$Y)..."

    # 3. Perform Remote Extraction
    echo "Extracting $FILE_NAME..."
    pmtiles extract "$SOURCE_URL" "$LOCAL_PATH" \
        --bbox="$LEFT,$BOTTOM,$RIGHT,$TOP"

    if [ $? -eq 0 ]; then
        # 4. Upload to R2 immediately
        echo "Uploading $FILE_NAME to R2..."
        aws s3 cp "$LOCAL_PATH" "s3://$R2_BUCKET/$FILE_NAME" \
            --endpoint-url "$R2_ENDPOINT"

        if [ $? -eq 0 ]; then
            echo "Successfully uploaded $FILE_NAME. Deleting local copy..."
            # 5. Delete local file to save space
            rm "$LOCAL_PATH"
        else
            echo "ERROR: Failed to upload $FILE_NAME to R2. Local file kept for retry."
        fi
    else
        echo "WARNING: Failed to extract Zone $i"
    fi
done

echo "--------------------------------------------------------"
echo "Process finished."