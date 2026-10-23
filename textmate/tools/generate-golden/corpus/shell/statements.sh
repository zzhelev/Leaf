#!/usr/bin/env bash
# Commands after keywords, which the grammar finds with a look-behind.
set -euo pipefail

name="${1:-world}"
if [ -n "$name" ]; then echo "hello, $name"; fi

for file in *.txt; do
  wc -l "$file" | sort -n
done

count=0
while read -r line; do
  count=$((count + 1))
done < input.txt

until [[ $count -le 0 ]]; do count=$((count - 1)); done

if grep -q main <<< "$branches"; then
  git switch main
elif git rev-parse --verify develop >/dev/null 2>&1; then
  git switch develop
else
  printf '%s\n' "no branch" >&2
fi

greet() {
  local who=$1
  echo "Hi $who" && return 0
}
greet "$(whoami)" | tee -a log.txt
