# syntax=docker/dockerfile:1.7

ARG STOVE_VERSION=dev

FROM node:24-bookworm-slim AS spa-builder

ARG STOVE_VERSION
WORKDIR /source/tools/stove-cli/spa
COPY gradle.properties /source/gradle.properties
COPY tools/stove-cli/spa/package.json tools/stove-cli/spa/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY tools/stove-cli/spa/ ./
RUN STOVE_VERSION="${STOVE_VERSION}" npm run build

FROM rust:1-bookworm AS rust-builder

ARG STOVE_VERSION
RUN apt-get update \
    && apt-get install --yes --no-install-recommends \
      libssl-dev \
      libpq-dev \
      libprotobuf-dev \
      pkg-config \
      protobuf-compiler \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /source/tools/stove-cli
COPY tools/stove-cli/Cargo.toml tools/stove-cli/Cargo.lock ./
RUN --mount=type=cache,target=/usr/local/cargo/registry \
    --mount=type=cache,target=/usr/local/cargo/git \
    cargo fetch --locked

WORKDIR /source
COPY gradle.properties ./
COPY lib/stove-dashboard-api/src/main/proto/ lib/stove-dashboard-api/src/main/proto/
COPY tools/stove-cli/ tools/stove-cli/
COPY --from=spa-builder /source/tools/stove-cli/spa/dist/ tools/stove-cli/spa/dist/

WORKDIR /source/tools/stove-cli
RUN --mount=type=cache,target=/usr/local/cargo/registry \
    --mount=type=cache,target=/usr/local/cargo/git \
    --mount=type=cache,target=/source/tools/stove-cli/target \
    STOVE_VERSION="${STOVE_VERSION}" SKIP_SPA_BUILD=1 cargo build --locked --release \
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
