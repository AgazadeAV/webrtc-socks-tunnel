# ====== STAGE 1: BUILD (Maven + JDK 21) ======
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /src
# Сначала подкидываем только pom'ы для кэша зависимостей
COPY pom.xml ./
COPY common/pom.xml common/pom.xml
COPY transport-webrtc/pom.xml transport-webrtc/pom.xml
COPY controller/pom.xml controller/pom.xml
COPY agent/pom.xml agent/pom.xml

# Тянем зависимости (прогреваем кэш)
RUN mvn -B -q -e -DskipTests dependency:go-offline

# Теперь весь исходник
COPY . .

# Собираем всё: parent + модули. Итоговый jar для controller: controller/target/controller.jar
RUN mvn -B -e -DskipTests clean package

# ====== STAGE 2: RUNTIME (JRE 21 slim) ======
FROM eclipse-temurin:21-jre-jammy AS runtime

# Базовые либы для нативной части WebRTC
RUN apt-get update && apt-get install -y --no-install-recommends \
    libstdc++6 \
    libglib2.0-0 \
    libnss3 \
    libasound2 \
    libpulse0 \
    libx11-6 \
    libxext6 \
    libxfixes3 \
    libxcb1 \
    libxrandr2 \
    ca-certificates \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Забираем жирный jar контроллера (assembly настроен в pom'ах)
COPY --from=build /src/controller/target/controller.jar /app/controller.jar

# По умолчанию — чистый запуск контроллера с интерактивным CLI
# (stdin доступен, если контейнер запущен с -it)
CMD ["java","-jar","/app/controller.jar"]
