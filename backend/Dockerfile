FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B -q package -DskipTests

FROM eclipse-temurin:21-jre
RUN groupadd --system app && useradd --system --gid app app
COPY --from=build /src/target/fineprint-0.1.0.jar /app/app.jar
USER app
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
