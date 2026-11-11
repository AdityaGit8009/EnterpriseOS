FROM node:24-alpine AS build
WORKDIR /workspace
COPY apps/web/package*.json ./apps/web/
RUN cd apps/web && npm install --no-audit --no-fund
COPY apps/web ./apps/web
COPY scripts/build-web.cjs ./scripts/build-web.cjs
RUN node scripts/build-web.cjs
FROM nginx:alpine
COPY infra/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/apps/web/dist /usr/share/nginx/html
EXPOSE 80
