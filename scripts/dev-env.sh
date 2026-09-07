# Homebrew's libpq and OpenSSL are keg-only. Expose them inside mise sessions.
if [ "$(uname -s)" = Darwin ] && command -v brew >/dev/null 2>&1; then
  stove_brew_prefix=$(brew --prefix)
  export PATH="$stove_brew_prefix/opt/libpq/bin:$PATH"
  export PKG_CONFIG_PATH="$stove_brew_prefix/opt/libpq/lib/pkgconfig:$stove_brew_prefix/opt/openssl@3/lib/pkgconfig${PKG_CONFIG_PATH:+:$PKG_CONFIG_PATH}"
  unset stove_brew_prefix
fi
