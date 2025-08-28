# 🚀 Пошаговая установка Proxychains + SOCKS5 + Impacket

## 🔹 1. Установка Proxychains

```bash
sudo apt update
sudo apt install proxychains4 -y
```

Проверим:

```bash
proxychains4 -h
```

---

## 🔹 2. Настройка Proxychains под твой SOCKS5

Откроем конфиг:

```bash
sudo nano /etc/proxychains.conf
```

В самом низу замени (или добавь):

```
socks5  127.0.0.1 1080
```

💡 Теперь Proxychains будет использовать твой SOCKS5 сервер на `127.0.0.1:1080`.

Проверим:

```bash
proxychains4 curl api.ipify.org
```

(должен показать IP твоего SOCKS5-сервера, а не твой реальный).

---

## 🔹 3. Установка Impacket

Сначала зависимости:

```bash
sudo apt install python3-pip python3-venv git -y
sudo apt install python-is-python3 -y
```

Скачаем и установим:

```bash
git clone https://github.com/fortra/impacket.git ~/impacket
cd ~/impacket
pip install . --break-system-packages
```

---

## 🔹 4. Добавление всех утилит Impacket в PATH

Сделаем симлинки автоматически:

```bash
nano install_impacket_links.sh
```

Вставляем в файл скрипт ниже:

```bash
#!/bin/bash
set -e

# Папка с утилитами impacket
IMPACKET_DIR=~/impacket/examples
# Папка для бинарников пользователя
LOCAL_BIN=~/.local/bin

# Создаём ~/.local/bin если ещё нет
mkdir -p "$LOCAL_BIN"

echo "[*] Создаём симлинки для всех .py из $IMPACKET_DIR"

for f in "$IMPACKET_DIR"/*.py; do
    name=$(basename "$f" .py)                 # убираем расширение
    target="$LOCAL_BIN/impacket-$name"        # итоговое имя
    ln -sf "$f" "$target"
    echo " [+] $target -> $f"
done

# Добавляем ~/.local/bin в PATH, если его нет
if ! grep -q 'export PATH=.*~/.local/bin' ~/.bashrc; then
    echo 'export PATH=$PATH:~/.local/bin' >> ~/.bashrc
    echo "[*] Добавил ~/.local/bin в PATH (будет доступно после перезапуска shell или source ~/.bashrc)"
else
    echo "[*] ~/.local/bin уже в PATH"
fi

echo "[✔] Установка завершена. Проверь, например:"
echo "    impacket-smbclient -h"
```

Сделай исполняемым:

```bash
chmod +x install_impacket_links.sh
```

Запусти:

```bash
./install_impacket_links.sh
```

---

## 🔹 5. Проверка

* Proxychains:

```bash
proxychains4 curl api.ipify.org
```

* Impacket:

```bash
impacket-smbclient -h
impacket-psexec -h
impacket-wmiexec -h
impacket-secretsdump -h
```

Если утилиты показывают `usage` и help → значит всё установлено корректно.

---

✅ После этих шагов у тебя будут:

* Proxychains, настроенный на SOCKS5 `127.0.0.1:1080`.
* Все инструменты Impacket (`impacket-smbclient`, `impacket-psexec`, `impacket-wmiexec`, и десятки других) в `$PATH`, доступные глобально.
