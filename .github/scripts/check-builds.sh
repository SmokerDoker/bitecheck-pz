#!/usr/bin/env bash
# Checks the two builds of a release (a/ and b/, from build-mod.yml) and puts the jars that the
# build record lists into final/<i>.jar:
#   rebuild, published: both builds must be byte-identical; final = their jars
#   workshop: the Workshop item's jars (workshop/<path>, from fetch-workshop.sh) must have exactly
#             the files that both builds produced (bcz jar-compare); final = the Workshop jars
# Both builders must have used the same game files (game.txt). Scans the final jars into the step
# summary. Exit 1 if the builds do not match. Hard findings (bcz scan exit 14) are recorded in the
# build record; such a release is verified only with a signed exception from a trusted reviewer
# (bcz except). With HARD_FINDINGS=fail (pull requests) they fail the check, so reviewers see them.
# Usage: check-builds.sh <bcz> <release.toml> <registry dir>
set -euo pipefail
bcz="$1" release="$2" registry="$3"
summary="${GITHUB_STEP_SUMMARY:-/dev/null}"
info="$("$bcz" release-info "$release" --registry "$registry")"
type="$(sed -n 's/^type=//p' <<<"$info")"
read -ra artifacts <<<"$(sed -n 's/^artifacts=//p' <<<"$info")"

if [ -f a/game.txt ] || [ -f b/game.txt ]; then
  if ! cmp -s a/game.txt b/game.txt; then
    { echo "❌ The game updated between the two builds, re-run the workflow."; echo '```'; diff a/game.txt b/game.txt || true; echo '```'; } >> "$summary"
    echo "::error::the game updated between the two builds, re-run"
    exit 1
  fi
  { echo "Both runners compiled against the same game files:"; echo '```'; cat a/game.txt; echo '```'; } >> "$summary"
fi

rm -rf final
mkdir final
if [ "$type" = workshop ]; then
  read -ra inItem <<<"$(sed -n 's/^workshop_artifacts=//p' <<<"$info")"
  for i in "${!artifacts[@]}"; do
    jar="workshop/${inItem[$i]}"
    for builder in a b; do
      if ! "$bcz" jar-compare "$builder/$i.jar" "$jar" > "compare-$builder.txt"; then
        { echo "❌ \`${inItem[$i]}\` in the Workshop item is not what builder $builder built from \`${artifacts[$i]}\`:"; echo '```'; head -n 50 "compare-$builder.txt"; echo '```'; } >> "$summary"
        echo "::error::the Workshop jar ${inItem[$i]} does not match the build from source"
        exit 1
      fi
    done
    cp "$jar" "final/$i.jar"
  done
  { echo "✅ The Workshop jars have exactly the files both runners built from source:"; echo '```'; (cd final && sha256sum ./*.jar) | while read -r sha f; do i="${f#./}"; i="${i%.jar}"; echo "$sha  ${inItem[$i]}"; done; echo '```'; } >> "$summary"
else
  if ! diff -q a/sha256.txt b/sha256.txt > /dev/null; then
    { echo "❌ The two builds differ. See CONTRIBUTING.md for reproducible builds."; echo '```'; diff a/sha256.txt b/sha256.txt || true; echo '```'; } >> "$summary"
    # what differs inside the jars: the files and classes, so the author sees what is not reproducible
    for jar in a/*.jar; do
      other="b/$(basename "$jar")"
      if [ -f "$other" ] && ! cmp -s "$jar" "$other"; then
        { echo "Differences in \`$(basename "$jar")\`:"; echo '```'; "$bcz" jar-compare "$jar" "$other" 2>&1 | head -n 60 || true; echo '```'; } >> "$summary"
      fi
    done
    echo "::error::the two builds differ"
    exit 1
  fi
  i=0
  while read -r sha _; do
    test "$(sha256sum "a/$i.jar" | cut -d' ' -f1)" = "$sha"
    cp "a/$i.jar" "final/$i.jar"
    i=$((i + 1))
  done < a/sha256.txt
  { echo "✅ Both runners produced identical jars:"; echo '```'; cat a/sha256.txt; echo '```'; } >> "$summary"
fi

status=0
hard=0
for jar in final/*.jar; do
  "$bcz" scan "$jar" --format markdown >> "$summary" || { code=$?; if [ "$code" -eq 14 ]; then hard=1; else status=$code; fi; }
  # bundled libraries against known vulnerabilities: a hint for the reviewers, never a block
  "$bcz" osv "$jar" --format markdown >> "$summary" 2>&1 || echo "::warning::$(basename "$jar"): a bundled library has known vulnerabilities (see the summary)"
done
if [ "$hard" -eq 1 ]; then
  msg="hard scanner findings: this release can only be verified with an exception from a trusted reviewer (bcz except), after the build record exists"
  echo "**⛔ ${msg}.**" >> "$summary"
  if [ "${HARD_FINDINGS:-record}" = fail ] && [ "$status" -eq 0 ]; then
    echo "::error::${msg}"
    status=14
  else
    echo "::warning::${msg}"
  fi
fi
exit $status
