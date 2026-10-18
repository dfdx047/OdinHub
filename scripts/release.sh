#!/usr/bin/env bash
# Publica uma versão do Odin Hub no GitHub a partir DESTE computador:
#   1. lê a versão (versionName) do app/build.gradle.kts
#   2. tira as notas dessa versão do CHANGELOG.md
#   3. corre os testes e compila o APK de release (assinado com a chave deste computador)
#   4. cria a tag, envia tudo e publica a release com o APK e as notas
#
# Uso (na raiz do projeto):   ./scripts/release.sh
# Precisa do GitHub CLI com sessão iniciada:   sudo pacman -S github-cli && gh auth login
set -euo pipefail
cd "$(dirname "$0")/.."

die() { echo "✖ $*" >&2; exit 1; }

command -v gh >/dev/null || die "GitHub CLI não encontrado. Instale com: sudo pacman -S github-cli && gh auth login"
gh auth status >/dev/null 2>&1 || die "GitHub CLI sem sessão. Corra: gh auth login"

VERSION=$(grep -E 'versionName *= *"' app/build.gradle.kts | head -n1 | sed -E 's/.*"([^"]+)".*/\1/')
[ -n "$VERSION" ] || die "Não encontrei o versionName em app/build.gradle.kts"
TAG="v$VERSION"

[ -z "$(git status --porcelain)" ] || die "Há alterações por comitar. Faça commit primeiro."
git rev-parse "$TAG" >/dev/null 2>&1 && die "A tag $TAG já existe. Suba o versionName/versionCode."

# Notas: tudo entre "## vX.Y" e o próximo "## v" no CHANGELOG.md.
NOTES=$(mktemp)
awk -v tag="$TAG" '
  $0 ~ "^## " tag "([ (].*)?$" { on = 1; next }
  on && /^## v/ { exit }
  on { print }
' CHANGELOG.md > "$NOTES"
[ -s "$NOTES" ] || die "Não há secção \"## $TAG\" no CHANGELOG.md."

echo "▶ Odin Hub $TAG — testes e APK de release…"
./gradlew --quiet testDebugUnitTest assembleRelease

APK_SRC=app/build/outputs/apk/release/app-release.apk
[ -f "$APK_SRC" ] || die "APK não encontrado em $APK_SRC (está assinado? veja o README, secção Signing)."
mkdir -p build/release
APK="build/release/OdinHub-$TAG.apk"
cp "$APK_SRC" "$APK"

echo "▶ Tag $TAG e envio para o GitHub…"
git tag -a "$TAG" -m "Odin Hub $TAG"
git push origin HEAD
git push origin "$TAG"

echo "▶ A publicar a release…"
gh release create "$TAG" "$APK" --title "Odin Hub $TAG" --notes-file "$NOTES" --latest
rm -f "$NOTES"
echo "✔ Publicada: $(gh release view "$TAG" --json url -q .url)"
