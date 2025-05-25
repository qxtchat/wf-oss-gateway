FROM golang:1.20-alpine AS build
WORKDIR /app
COPY . .
RUN go mod tidy && go build -o go-token-service main.go

FROM alpine:3.18
WORKDIR /app
COPY --from=build /app/go-token-service /app/
COPY --from=build /app/main.go /app/
EXPOSE 2447
CMD ["/app/go-token-service"] 