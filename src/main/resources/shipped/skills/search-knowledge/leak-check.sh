#!/bin/sh
# Adapted from Varde (https://github.com/oyvij/varde-editor). Copyright (c) 2026 Øyvind Johannessen. MIT License: see LICENSE.
# Usage: leak-check.sh <staging folder>
# Prints every staged line that names this machine: the home folder, the user name, the host name
# or anything that looks like an absolute path. Prints "clean" and exits 0 when there are none.
staged="${1:?usage: leak-check.sh <staging folder>}"
found=0
home="${HOME:-$USERPROFILE}"
user="$(id -un 2>/dev/null || printf '%s' "${USER:-$USERNAME}")"
host="$(hostname 2>/dev/null || uname -n)"
[ -n "$home" ] && grep -rnF -e "$home" "$staged" && found=1
for word in "$user" "$host" "${host%%.*}"; do
  [ -n "$word" ] && grep -rniwF -e "$word" "$staged" && found=1
done
grep -rnE -e '(^|[^[:alnum:]:/.~_-])(~/|file:/|/[[:alnum:]._-]+/|[A-Za-z]:[\\/])' "$staged" && found=1
[ "$found" = 0 ] && echo "clean"
exit "$found"
