# Ants in a container, from the jar `./gradlew bootJar` builds, with the CLIs its Tasks run. Configured from the environment:
#   ANTS_PASSWORD, or ANTS_PASSWORD_FILE naming a file that holds it, such as a Docker secret. Required.
#   SPRING_DATASOURCE_URL, SPRING_DATASOURCE_USERNAME and SPRING_DATASOURCE_PASSWORD: Postgres for engine state,
#   such as jdbc:postgresql://db/ants. Without them the engine is H2 on the volume.
# Everything else Ants keeps, its home and the CLIs' logins included, is on the one volume, /data.

# claude needs a newer Node than Ubuntu's, so Node and the npm CLIs come from Node's own image.
FROM node:24-bookworm-slim AS clis
RUN npm install --global @anthropic-ai/claude-code @openai/codex

FROM eclipse-temurin:25-jre
COPY --from=clis /usr/local/bin/ /usr/local/bin/
COPY --from=clis /usr/local/lib/node_modules/ /usr/local/lib/node_modules/
RUN apt-get update \
    && apt-get install --yes --no-install-recommends git gh \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir /data \
    && useradd --home-dir /data/home --create-home ants \
    && chown ants /data

ENV ANTS_DOCKER=true \
    ANTS_HOME=/data \
    SERVER_ADDRESS=0.0.0.0 \
    SHELL=/bin/bash
USER ants
VOLUME /data
EXPOSE 7323
COPY build/libs/ants.jar /opt/ants/ants.jar
ENTRYPOINT ["java", "-jar", "/opt/ants/ants.jar"]
