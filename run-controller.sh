#!/bin/bash
set -e

# === Настройки ===
REPO_URL="https://github.com/AgazadeAV/webrtc-socks-tunnel.git"
JAVA_REQUIRED="21"   # нужная версия Java (major)

# === 1. Клонирование репозитория ===
WORKDIR="$HOME/controller_build"
rm -rf "$WORKDIR"
git clone "$REPO_URL" "$WORKDIR"

# === 2. Проверка версии Java ===
if ! command -v java &>/dev/null; then
  echo "❌ Java не установлена. Установи OpenJDK $JAVA_REQUIRED и запусти снова."
  exit 1
fi

JAVA_VERSION=$(java -version 2>&1 | awk -F[\".] '/version/ {print $2}')
if [ "$JAVA_VERSION" != "$JAVA_REQUIRED" ]; then
  echo "❌ Требуется Java $JAVA_REQUIRED, а найдена версия $JAVA_VERSION"
  exit 1
fi

echo "✅ Java $JAVA_REQUIRED найдена."

# === 3. Проверка Maven ===
if ! command -v mvn &>/dev/null; then
  echo "⚠️ Maven не найден, устанавливаю..."
  if [ -f /etc/debian_version ]; then
    sudo apt-get update && sudo apt-get install -y maven
  elif [ -f /etc/redhat-release ]; then
    sudo yum install -y maven
  else
    echo "❌ Неизвестная ОС. Установи Maven вручную."
    exit 1
  fi
fi

echo "✅ Maven установлен."

# === 4. Переход в папку репозитория ===
cd "$WORKDIR"

# === 5. Сборка проекта ===
echo "⚙️ Запускаю mvn clean install..."
mvn clean install -DskipTests

# === 6. Запуск контроллера ===
echo "🚀 Запускаю контроллер..."
java -jar controller/target/controller.jar
