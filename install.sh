#!/bin/bash
# Installs Ants, with its own Java runtime, and starts it:
#   curl -fsSL https://github.com/oyvij/ants/releases/latest/download/install.sh | bash
# Each version goes in its own folder, $ANTS_HOME/versions/<version>; $ANTS_HOME/current links the one in use and
# ~/.local/bin/ants links its launcher. ANTS_RELEASES names another release folder to install from.
set -euo pipefail

releases=${ANTS_RELEASES:-https://github.com/oyvij/ants/releases/latest/download}
releases=${releases%/}
export ANTS_HOME=${ANTS_HOME:-$HOME/.ants}
link=$HOME/.local/bin/ants

download=$(mktemp -d)
trap 'rm -rf "$download"' EXIT
cd "$download"

curl -fsSL -o SHA256SUMS "$releases/SHA256SUMS"
tarball=$(awk '/macos-arm64\.tar\.gz$/ { print $2 }' SHA256SUMS)
version=${tarball#ants-}
version=${version%-macos-arm64.tar.gz}
echo "Downloading Ants $version"
curl -fSL --progress-bar -o "$tarball" "$releases/$tarball"
if ! shasum -a 256 -c SHA256SUMS > /dev/null 2>&1; then
  echo "The download doesn't match its checksum; nothing was installed" >&2
  exit 1
fi

mkdir -p unpacked "$ANTS_HOME/versions" "$(dirname "$link")"
tar -xzf "$tarball" -C unpacked
rm -rf "${ANTS_HOME:?}/versions/$version"
mv unpacked "$ANTS_HOME/versions/$version"
ln -sfn "versions/$version" "$ANTS_HOME/current"
ln -sfn "$ANTS_HOME/current/bin/ants" "$link"
echo "Installed Ants $version in $ANTS_HOME/versions/$version"

case ":$PATH:" in
  *":$(dirname "$link"):"*) ;;
  *) echo "Add $(dirname "$link") to your PATH to run ants by name" ;;
esac
"$link" start
