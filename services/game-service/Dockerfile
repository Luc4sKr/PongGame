FROM golang:1.24-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY cmd ./cmd
COPY internal ./internal
RUN CGO_ENABLED=0 GOOS=linux go build -o /out/game-service ./cmd/game-service

FROM alpine:3.21
WORKDIR /app
RUN apk add --no-cache ca-certificates wget
COPY --from=build /out/game-service /app/game-service
EXPOSE 8080
ENTRYPOINT ["/app/game-service"]
