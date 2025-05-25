FROM maven:3.8.5-openjdk-17 AS build
WORKDIR /app
COPY . .
RUN mvn clean package -DskipTests

FROM openjdk:17-jdk-slim
WORKDIR /app
COPY --from=build /app/distribution/target/distribution-*-bundle-tar.tar.gz /app/
RUN tar -xzf /app/distribution-*-bundle-tar.tar.gz -C /app && \
    rm /app/distribution-*-bundle-tar.tar.gz
COPY broker/config/wildfirechat.conf /app/config/wildfirechat.conf
EXPOSE 8884
CMD ["sh", "/app/bin/wildfirechat.sh"] 