FROM eclipse-temurin:21-jdk

WORKDIR /app

COPY DispatchApiServer.java .

RUN javac DispatchApiServer.java

CMD ["java", "DispatchApiServer"]
