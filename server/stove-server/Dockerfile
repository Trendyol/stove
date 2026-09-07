# syntax=docker/dockerfile:1.7

ARG STOVE_VERSION=dev

FROM node:24-bookworm-slim AS node-toolchain

FROM rust:1-bookworm AS rust-builder

# Type generation needs both Rust and Node before the SPA can be bundled.
COPY --from=node-toolchain /usr/local/ /usr/local/

ARG STOVE_VERSION
RUN apt-get update \
    && apt-get install --yes --no-install-recommends \
      libssl-dev \
      libpq-dev \
      libprotobuf-dev \
      pkg-config \
      protobuf-compiler \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /source/server/stove-server
COPY server/stove-server/Cargo.toml server/stove-server/Cargo.lock ./
RUN --mount=type=cache,target=/usr/local/cargo/registry \
    --mount=type=cache,target=/usr/local/cargo/git \
    cargo fetch --locked

WORKDIR /source/server/stove-server/spa
COPY server/stove-server/spa/package.json server/stove-server/spa/package-lock.json ./
COPY server/stove-server/spa/scripts/api-types/package.json scripts/api-types/package.json
RUN --mount=type=cache,target=/root/.npm npm ci

WORKDIR /source
COPY gradle.properties ./
COPY lib/stove-dashboard-api/src/main/proto/ lib/stove-dashboard-api/src/main/proto/
COPY server/stove-server/ server/stove-server/

WORKDIR /source/server/stove-server
RUN --mount=type=cache,target=/usr/local/cargo/registry \
    --mount=type=cache,target=/usr/local/cargo/git \
    --mount=type=cache,target=/source/server/stove-server/target \
    --mount=type=cache,target=/root/.npm \
    cd spa && STOVE_VERSION="${STOVE_VERSION}" npm run build \
    && cd .. && STOVE_VERSION="${STOVE_VERSION}" SKIP_SPA_BUILD=1 cargo build --locked --release \
    && cp target/release/stove /usr/local/bin/stove

FROM debian:bookworm-slim AS runtime

RUN apt-get update \
    && apt-get install --yes --no-install-recommends ca-certificates libpq5 libssl3 \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 stove \
    && useradd --uid 10001 --gid stove --create-home --home-dir /home/stove stove \
    && mkdir /data \
    && chown stove:stove /data

COPY --from=rust-builder /usr/local/bin/stove /usr/local/bin/stove

USER stove
WORKDIR /home/stove
ENV HOME=/home/stove

EXPOSE 4040 4041

ENTRYPOINT ["/usr/local/bin/stove"]
CMD ["--db", "/data/stove.db", "--no-skills-check"]
