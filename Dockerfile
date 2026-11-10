# 시간 적금 — 프론트 빌드 + 백엔드 jar 하나로 묶는 배포용 이미지
FROM node:22-alpine AS web
WORKDIR /app/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM maven:3.9-eclipse-temurin-21 AS api
WORKDIR /app/backend
COPY backend/ ./
COPY --from=web /app/frontend/dist/ ./src/main/resources/static/
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /srv
COPY --from=api /app/backend/target/time-savings-0.1.0.jar app.jar
# H2 파일 DB(./data)는 컨테이너 안에 생긴다. 무료 호스팅에서는 재시작하면 데모 데이터로 다시 시작한다.
ENV JAVA_OPTS="-Xmx384m"
EXPOSE 8080
CMD ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
