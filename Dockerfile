FROM confluentinc/cp-base:3.2.1-5

ENV TERM=dumb

RUN mkdir /code
WORKDIR /code

COPY gradle /code/gradle
COPY gradle.properties build.gradle settings.gradle  gradlew /code/
RUN ./gradlew downloadDependencies
COPY src /code/src

ENTRYPOINT ["./gradlew"]
