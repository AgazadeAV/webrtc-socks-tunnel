# 🐳 Установка Docker на Linux и запуск контроллера

## 📋 Поддерживаемые дистрибутивы

* Ubuntu / Debian

---

## 🔹 Установка Docker Engine

1. Удалим старые версии (если есть):

```bash
sudo apt-get remove docker docker-engine docker.io containerd runc
```

2. Обновим пакеты и установим зависимости:

```bash
sudo apt-get update
sudo apt-get install -y ca-certificates curl gnupg lsb-release
```

3. Добавим ключ и репозиторий:

```bash
sudo mkdir -p /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/$(. /etc/os-release; echo "$ID")/gpg | \
  sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg

echo \
  "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
  https://download.docker.com/linux/$(. /etc/os-release; echo "$ID") \
  $(lsb_release -cs) stable" | \
  sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
```

4. Устанавливаем Docker и плагины:

```bash
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
```

5. Проверим:

```bash
docker --version
```

---

## 🔹 Запуск Docker без `sudo`

По умолчанию Docker требует `sudo`. Чтобы запускать от обычного пользователя:

```bash
sudo usermod -aG docker $USER
newgrp docker
```

Теперь можно проверять:

```bash
docker run hello-world
```

---

## 🔹 Сборка и запуск контейнера

### 1. Сборка образа

```bash
docker build -t webrtc-controller:latest .
```

* `docker build` — собирает образ из `Dockerfile` в текущей директории (`.`).
* `-t webrtc-controller:latest` — присваивает имя и тег образу.
  В данном случае образ будет называться **`webrtc-controller`**, а тег — **`latest`**.

> ⚠️ Эту команду нужно выполнить **только один раз** (или если вы изменили `Dockerfile` или код).
> При повторных запусках приложения **собирать заново не требуется**.

---

### 2. Запуск контейнера

```bash
docker run --rm -it --network host webrtc-controller:latest
```

* `docker run` — запускает контейнер из указанного образа.
* `--rm` — автоматически удаляет контейнер после завершения работы.
* `-it` — включает интерактивный режим (можно видеть вывод программы и управлять).
* `--network host` — контейнер использует сетевой стек хоста (удобно для приложений, работающих с локальными портами).
* `webrtc-controller:latest` — имя и тег образа, собранного на предыдущем шаге.

> ⚠️ Для запуска программы достаточно использовать **только эту команду**.
> Повторно собирать образ не нужно, если код не менялся.

---

## ✅ Готово!

Теперь у вас установлен Docker, собран образ `webrtc-controller` и вы можете запускать приложение через одну команду:

```bash
docker run --rm -it --network host webrtc-controller:latest
```
