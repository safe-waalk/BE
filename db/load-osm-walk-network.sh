#!/usr/bin/env bash
set -euo pipefail

source .env

# MSYS_NO_PATHCONV=1: on Windows Git Bash (MSYS), any argument that looks
# like an absolute POSIX path (e.g. "/data/seoul.osm.pbf" or the "-v"
# mount's ":/data" target) gets silently rewritten to a Windows host path
# (e.g. "C:/Program Files/Git/data/seoul.osm.pbf") before it reaches
# docker.exe, which breaks both the volume mount and the --f argument.
# Setting MSYS_NO_PATHCONV=1 disables that rewriting. It's a no-op on
# Linux/macOS shells, so this is safe everywhere.

# iboates/osm2pgrouting:latest (osm2pgrouting 2.3.8) is linked only against
# libexpat (an XML parser) with no protobuf/PBF support (confirmed via
# `ldd`). Feeding it osm-data/seoul.osm.pbf directly fails with
# "not well-formed (invalid token) at line 1" because it tries to parse the
# binary PBF as XML. So first convert the PBF to OSM XML using
# iboates/osmium:latest (the same image Task 2 already uses for clipping),
# then load the XML file.
echo "Converting seoul.osm.pbf to OSM XML..."
MSYS_NO_PATHCONV=1 docker run --rm -v "$(pwd)/osm-data:/data" iboates/osmium:latest \
  cat /data/seoul.osm.pbf -o /data/seoul.osm --overwrite

# --password (not PGPASSWORD env var): osm2pgrouting does not read the
# PGPASSWORD environment variable automatically (verified: a run with only
# -e PGPASSWORD set failed with "fe_sendauth: no password supplied", while
# passing --password succeeded). It must be passed explicitly as a CLI arg.
#
# --chunk 1000000 (default is 20000): with the default chunk size, the load
# against the Supabase pooler (Supavisor session mode) reproducibly failed
# with "ERROR: relation "__ways1" does not exist" starting at the same way
# count (300000, i.e. after 15 chunk-transaction boundaries) on two separate
# runs -- osm2pgrouting's per-chunk temp table apparently doesn't survive
# whatever the pooler does across enough chunk boundaries. Seoul has
# ~476k ways, so a chunk size comfortably above that processes everything
# as a single transaction/temp-table lifetime and avoids the issue
# (verified: a run with --chunk 500000 completed with zero errors).
MSYS_NO_PATHCONV=1 docker run --rm -v "$(pwd)/osm-data:/data" \
  -e PGSSLMODE=require \
  iboates/osm2pgrouting:latest \
  --dbname "$SUPABASE_DB_NAME" \
  --username "$SUPABASE_DB_USER" \
  --host "$SUPABASE_DB_HOST" \
  --port "$SUPABASE_DB_PORT" \
  --password "$SUPABASE_DB_PASSWORD" \
  --clean \
  --chunk 1000000 \
  --f /data/seoul.osm
