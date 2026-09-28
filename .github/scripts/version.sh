#!/usr/bin/env bash
# Derives VERSION_NAME and VERSION_CODE from git tags and exports them through $GITHUB_ENV.
# Outside of Actions it only prints them, so it can be tried locally.
#
# Tags look like v[a].[b].[c], with a and b in 0-9 and c in 0-99. That range keeps
# versionCode = 1000*a + 100*b + c unique and increasing.
#
# Version names:
#   tag build:          a.b.c
#   untagged build:     <latest valid tag without the v>_<7-char commit>, for example 0.2.3_1a2b3c4
#   no valid tag yet:   0.1_<7-char commit>, with versionCode taken from 0.1.0 (100)
set -euo pipefail

TAG_RE='^v([0-9])\.([0-9])\.([0-9]{1,2})$'
ref="${GITHUB_REF:-}"
sha="$(git rev-parse --short=7 HEAD)"

code_of() {  # $1=a $2=b $3=c. The 10# prefix stops values like "08" from being read as octal.
  echo $(( 1000 * 10#$1 + 100 * 10#$2 + 10#$3 ))
}

if [[ "$ref" == refs/tags/* ]]; then
  tag="${ref#refs/tags/}"
  if [[ ! "$tag" =~ $TAG_RE ]]; then
    echo "::error::Tag '$tag' does not match v[a].[b].[c] (a and b: 0-9, c: 0-99)"
    exit 1
  fi
  a="${BASH_REMATCH[1]}"; b="${BASH_REMATCH[2]}"; c="${BASH_REMATCH[3]}"
  name="$a.$b.$((10#$c))"
  code="$(code_of "$a" "$b" "$c")"
else
  # Only tags reachable from this commit count, so an old branch never picks up a newer version.
  latest="$(git tag --merged HEAD | grep -E "$TAG_RE" | sort -V | tail -n 1 || true)"
  if [[ -n "$latest" && "$latest" =~ $TAG_RE ]]; then
    a="${BASH_REMATCH[1]}"; b="${BASH_REMATCH[2]}"; c="${BASH_REMATCH[3]}"
    name="$a.$b.$((10#$c))_$sha"
    code="$(code_of "$a" "$b" "$c")"
  else
    name="0.1_$sha"
    code="$(code_of 0 1 0)"
  fi
fi

if (( code < 1 )); then
  echo "::error::versionCode must be at least 1, so v0.0.0 cannot be used"
  exit 1
fi

echo "VERSION_NAME=$name"
echo "VERSION_CODE=$code"
if [[ -n "${GITHUB_ENV:-}" ]]; then
  { echo "VERSION_NAME=$name"; echo "VERSION_CODE=$code"; } >> "$GITHUB_ENV"
fi
