#!/bin/bash
# Installs Ants, with its own Java runtime, and starts it:
#   curl -fsSL https://github.com/oyvij/ants/releases/latest/download/install.sh | bash
# Each version goes in its own folder, $ANTS_HOME/versions/<version>; $ANTS_HOME/current links the one in use,
# $ANTS_HOME/previous the one before it, for `ants rollback`, and ~/.local/bin/ants links the launcher. ANTS_RELEASES
# names another release folder to install from. Ants' Update runs it with `--update <the version Ants runs>`, which only
# installs the version: the running Ants goes on and the new one runs from the next restart.
set -euo pipefail

update=${1:-}
running=${2:-}

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

mkdir -p unpacked "$ANTS_HOME/versions"
tar -xzf "$tarball" -C unpacked
rm -rf "${ANTS_HOME:?}/versions/$version"
mv unpacked "$ANTS_HOME/versions/$version"
# previous is the version current linked, or with --update the one Ants runs now, even when an Update before a restart
# moved current past it: that one never ran.
was=$(readlink "$ANTS_HOME/current" || true)
if [ "$update" = --update ] && [ -n "$running" ] && [ -d "$ANTS_HOME/versions/$running" ]; then was=versions/$running; fi
if [ -n "$was" ] && [ "$was" != "versions/$version" ]; then ln -sfn "$was" "$ANTS_HOME/previous"; fi
ln -sfn "versions/$version" "$ANTS_HOME/current"
echo "Installed Ants $version in $ANTS_HOME/versions/$version"
if [ "$update" = --update ]; then exit 0; fi

mkdir -p "$(dirname "$link")"
ln -sfn "$ANTS_HOME/current/bin/ants" "$link"

case ":$PATH:" in
  *":$(dirname "$link"):"*) ;;
  *) echo "Add $(dirname "$link") to your PATH to run ants by name" ;;
esac
"$link" start
