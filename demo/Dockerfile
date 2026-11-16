FROM maven:3.9-eclipse-temurin-25@sha256:93b8a14ea2f412782e4e842651273b4d903e35cc496284f178fbbe2d67d00976
WORKDIR /app
COPY pom.xml contract.ref ./
COPY src ./src
RUN mvn -B -DskipTests package org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy-dependencies \
    -DincludeScope=runtime
COPY demo/DictionaryHost.java ./demo/DictionaryHost.java
RUN mkdir -p demo/classes && javac --release 25 --add-modules jdk.httpserver \
    -cp 'target/classes:target/dependency/*' -d demo/classes demo/DictionaryHost.java
CMD ["java", "--add-modules", "jdk.httpserver", "-cp", \
    "/app/demo/classes:/app/target/classes:/app/target/dependency/*", \
    "org.alxarafe.arp.demo.DictionaryHost"]