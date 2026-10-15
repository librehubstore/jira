# Fluxo: one image = Spring Boot API + built front end (spec §10).

FROM node:24-alpine AS front
WORKDIR /front
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM eclipse-temurin:21-jdk AS back
WORKDIR /back
COPY backend/.mvn .mvn
COPY backend/mvnw backend/pom.xml ./
# jvm.config only exists for a Windows dev machine behind TLS inspection (see DECISIONS.md).
RUN rm -f .mvn/jvm.config && chmod +x mvnw && ./mvnw -q -B dependency:go-offline
COPY backend/src src
COPY --from=front /front/dist src/main/resources/static
RUN ./mvnw -q -B package -DskipTests && cp target/fluxo-*.jar /fluxo.jar

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 1001 fluxo && mkdir -p /data/attachments && chown -R fluxo /data
USER fluxo
COPY --from=back /fluxo.jar /app/fluxo.jar
ENV FLUXO_STORAGE_DIR=/data/attachments
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/fluxo.jar"]
