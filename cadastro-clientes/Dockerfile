# Etapa 1: compila a aplicação Java e empacota as dependências no JAR.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -B clean package

# Etapa 2: imagem menor para executar a aplicação.
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=build /app/target/cadastro-clientes-8.0.0.jar app.jar

# O aplicativo lê a variável PORT fornecida pelo serviço de hospedagem.
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
