FROM eclipse-temurin:17-jdk
WORKDIR /app
COPY . .
# Download the MySQL driver here so the .jar doesn't need to be in GitHub
RUN mkdir -p lib out uploads \
 && curl -fsSL -o lib/mysql-connector-j.jar \
    https://repo1.maven.org/maven2/com/mysql/mysql-connector-j/8.4.0/mysql-connector-j-8.4.0.jar \
 && javac -encoding UTF-8 -cp lib/mysql-connector-j.jar -d out src/Db.java src/Main.java
CMD ["java", "-cp", "out:lib/mysql-connector-j.jar", "Main", "config.properties", "frontend"]
