# 🚀 Деплой TURN + ICE API + Signal API на виртуалку (Ubuntu)

Этот гайд описывает полный процесс:

1. Копирование файлов с Windows на виртуальную машину.
2. Подключение к виртуальной машине по SSH.
3. Развёртывание сервисов с помощью скрипта.

---

## 📂 Подготовка

* У вас должны быть:

    * `ключ доступа` к виртуалке (`.pem` файл).
    * `Go-файлы` (`main-iceapi.go` и `main-signalapi.go`).
    * `скрипт` для установки TURN сервера и Go-сервисов (`deploy_turn.sh`).

* На Windows должны быть установлены:

    * `scp` (идёт вместе с OpenSSH).
    * `ssh` (идёт вместе с OpenSSH).

---

## ❗ 0. Примечание при первом подключении к виртуалке

При первом подключении может выскочить такое сообщение:

```
The authenticity of host '12.34.567.890 (12.34.567.890)' can't be established.
ED25519 key fingerprint is SHA256:v2wjhKzhjsciSrjBCcIJfJMtlDBL2IMILNbBKcO4PJY.
This key is not known by any other names.
Are you sure you want to continue connecting (yes/no/[fingerprint])?
```

В терминале просто прописываем `yes` и кликаем `Enter`

## 🔽 1. Копируем файлы на виртуалку (выполняем на **Windows**)

```powershell
scp -i "C:\Users\Administrator\Documents\turn-webrtc-server_key.pem" "C:\Users\Administrator\Documents\main-iceapi.go" azureuser@12.34.567.890:~/main-iceapi.go
scp -i "C:\Users\Administrator\Documents\turn-webrtc-server_key.pem" "C:\Users\Administrator\Documents\main-signalapi.go" azureuser@12.34.567.890:~/main-signalapi.go
scp -i "C:\Users\Administrator\Documents\turn-webrtc-server_key.pem" "C:\Users\Administrator\Documents\deploy_turn.sh" azureuser@12.34.567.890:~/deploy_turn.sh
```

👉 Здесь:

* `turn-webrtc-server_key.pem` — ваш приватный ключ.
* `main-iceapi.go`, `main-signalapi.go`, `deploy_turn.sh` — ваши Go-файлы и скрипт.
* `azureuser@12.34.567.890` — имя пользователя и публичный IP вашей виртуалки.

---

## 🔑 2. Подключаемся по SSH (выполняем на **Windows**)

```powershell
ssh -i "C:\Users\Administrator\Documents\turn-webrtc-server_key.pem" azureuser@12.34.567.890
```

---

## ⚙️ 3. Настраиваем и запускаем скрипт (выполняем на **виртуалке**)

После подключения к виртуалке:

```bash
# Создаём рабочие директории
sudo mkdir /iceapi /signalapi /deploy
sudo chown root:root /iceapi /signalapi /deploy

# Переносим загруженные файлы в нужные папки
sudo mv ~/main-iceapi.go /iceapi/main.go
sudo mv ~/main-signalapi.go /signalapi/main.go
sudo mv ~/deploy_turn.sh /deploy/deploy_turn.sh

# Делаем скрипт исполняемым
sudo chmod +x /deploy/deploy_turn.sh

# Запускаем деплой
sudo /deploy/deploy_turn.sh
```

---

## ✅ Проверка

После выполнения скрипта сервисы должны быть запущены.
Проверьте доступность API:

```bash
curl http://<PUBLIC_IP>:8080/ice?u=test
curl http://<PUBLIC_IP>:9090/health
```

---

📌 Теперь у вас есть полностью рабочие:

* **coturn** (TURN-сервер)
* **ICE API** (Go-сервис)
* **Signal API** (Go-сервис)
