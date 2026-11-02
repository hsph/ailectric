FROM debian:trixie-slim
RUN apt-get update && apt-get install -y --no-install-recommends \
        build-essential ca-certificates curl git libsqlite3-dev osmium-tool python3 zlib1g-dev \
    && git clone --depth 1 --branch 2.79.0 https://github.com/felt/tippecanoe /src \
    && make -C /src -j4 && make -C /src install && rm -rf /src
RUN arch=$(uname -m | sed 's/aarch64/arm64/') \
    && curl -fsSL "https://github.com/protomaps/go-pmtiles/releases/download/v1.31.2/go-pmtiles_1.31.2_Linux_$arch.tar.gz" \
    | tar -xz -C /usr/local/bin pmtiles
COPY build.py /usr/local/bin/build_overlays.py
ENTRYPOINT ["python3", "/usr/local/bin/build_overlays.py"]
