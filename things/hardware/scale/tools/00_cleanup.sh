#!/bin/bash
set -e
echo "Phase0 cleanup"
rm -rf /tmp/*.py /tmp/zero* /tmp/prog* /tmp/full_* /tmp/erc* /tmp/drc* /tmp/blank /tmp/scale.dsn /tmp/scale.ses 2>/dev/null || true
pkill -f rebuild_all 2>/dev/null || true
pkill -f rebuild 2>&1 | head || true
echo "cleaned"
