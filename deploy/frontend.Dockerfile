# Artifact-first: web/package-lock.json + npm ci/check/build produce web/dist.
FROM nginx:1.28.0-alpine3.21@sha256:30f1c0d78e0ad60901648be663a710bdadf19e4c10ac6782c235200619158284
COPY deploy/nginx.conf /etc/nginx/conf.d/default.conf
COPY web/dist/ /usr/share/nginx/html/
EXPOSE 80
