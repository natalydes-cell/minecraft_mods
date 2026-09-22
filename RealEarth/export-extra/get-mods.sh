#!/bin/sh
# Downloads the companion mods from their authors' own files on Modrinth,
# pinned to exact versions and checked by SHA-512.
#
# Nothing here is bundled: these are other people's mods under copyleft
# licences, so this fetches them from source rather than redistributing them.
#
# All of them are OPTIONAL. RealEarth runs without any of them.
set -e
DIR=$(cd "$(dirname "$0")" && pwd)
echo "Downloading into $DIR/../mods"
java -jar "$DIR/realearth-importer.jar" --mods "$DIR/../mods" \
     --manifest "$DIR/companion-mods.json" "$@"
