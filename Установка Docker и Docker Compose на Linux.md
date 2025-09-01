# 🐳 Установка Docker и Docker Compose на Linux

## 📋 Поддерживаемые дистрибутивы

* Ubuntu / Debian

---

## 🔹 Установка Docker Engine

1. Удалим старые версии:

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

## ✅ Готово!

Теперь у вас установлен Docker.
