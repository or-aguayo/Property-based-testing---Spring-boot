# Etapa de compilación
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw dependency:go-offline

COPY src ./src
RUN ./mvnw clean package -DskipTests

# Etapa de ejecución
FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=build /app/target/app-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080

ENTRYPOINT ["java","-jar","app.jar"]
