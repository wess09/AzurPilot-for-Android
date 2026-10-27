FROM golang:1.26.1@sha256:cd78d88e00afadbedd272f977d375a6247455f3a4b1178f8ae8bbcb201743a8a AS build
WORKDIR /src
COPY go.mod ./
COPY *.go ./
RUN CGO_ENABLED=0 go test ./... \
    && CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/device-report .

FROM busybox:1.37.0-musl@sha256:5cec3fc171c87218698e85a52af7087de727372aae264a787b8112901a5b0092 AS healthcheck

FROM gcr.io/distroless/static-debian13:nonroot@sha256:18f4cc26c9ffa7f20a359298be7deadac5c607c8e03a02b8119f1a5fc2f82c99
COPY --from=healthcheck /bin/busybox /busybox
COPY --from=build /out/device-report /device-report
USER 65532:65532
ENV LISTEN_ADDR=127.0.0.1:18882 \
    CLIENT_CERT_FILE=/run/secrets/client-cert.pem \
    STATE_FILE=/var/lib/azurpilot-device-report/state.json \
    GOMEMLIMIT=48MiB \
    GOMAXPROCS=1
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD ["/busybox", "wget", "-q", "-O", "/dev/null", "http://127.0.0.1:18882/healthz"]
ENTRYPOINT ["/device-report"]
