# syntax=docker/dockerfile:1
FROM node:24-alpine AS build
WORKDIR /src
COPY travel-h5-java/package.json travel-h5-java/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY travel-h5-java/ ./
RUN npm run build

FROM caddy:2-alpine
COPY deploy/Caddyfile /etc/caddy/Caddyfile
COPY --from=build /src/dist /srv
EXPOSE 8443 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=15s --retries=10 CMD wget -q -O - http://127.0.0.1:8080/ping > /dev/null || exit 1
