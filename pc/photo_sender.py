"""PhotoSync: отправляет файлы с компа на телефон (или планшет) в режиме «Приём».

Дома — напрямую по Wi-Fi, вне дома — через интернет (ключ телефон отдаёт при первой отправке по Wi-Fi).
Запуск: py photo_sender.py файл1 файл2 папка ...
Удобнее: перетащить файлы на send_to_phone.bat или ПКМ → Отправить → PhotoSync.
"""
import http.client
import json
import os
import re
import socket
import sys
import time
import urllib.parse
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from photo_receiver import APP_DIR, HTTP_PORT, RelayCipher, clawd_event, local_ips, relay_url

CONFIG_FILE = APP_DIR / "sender_config.json"


def load_config():
    try:
        return json.loads(CONFIG_FILE.read_text("utf-8"))
    except (OSError, ValueError):
        return {}


def save_config(cfg):
    CONFIG_FILE.write_text(json.dumps(cfg, ensure_ascii=False), "utf-8")


def connect(host, src, timeout=5):
    # привязываемся к Wi-Fi адресу, чтобы запрос не ушёл в VPN
    return http.client.HTTPConnection(host, HTTP_PORT, timeout=timeout, source_address=(src, 0))


LAST_RELAY_KEY = {}  # host -> ключ из последнего удачного ping


def ping(host, src, code):
    """Возвращает (статус, имя устройства) или None, если там никого."""
    try:
        conn = connect(host, src, timeout=2)
        conn.request("GET", "/ping", headers={"X-Code": code or ""})
        resp = conn.getresponse()
        text = resp.read().decode("utf-8", "replace")
        key = resp.getheader("X-Relay-Key") or ""
        conn.close()
        if resp.status == 200 and re.fullmatch(r"[0-9a-f]{64}", key):
            LAST_RELAY_KEY[host] = key
        return resp.status, text
    except OSError:
        return None


def port_open(host, src):
    try:
        with socket.create_connection((host, HTTP_PORT), timeout=0.6, source_address=(src, 0)):
            return True
    except OSError:
        return False


def scan(src, own):
    """Ищет в домашней сети всех, у кого открыт порт PhotoSync."""
    base = src.rsplit(".", 1)[0]
    hosts = [f"{base}.{i}" for i in range(1, 255) if f"{base}.{i}" not in own]
    with ThreadPoolExecutor(64) as pool:
        return [h for h, ok in zip(hosts, pool.map(lambda h: port_open(h, src), hosts)) if ok]


def find_target(cfg, src, own):
    # сначала пробуем телефон, на который отправляли в прошлый раз
    host = cfg.get("host")
    if host:
        res = ping(host, src, cfg.get("code"))
        if res and res[0] == 200:
            return host, res[1]

    print("Ищу телефон в сети...")
    candidates = []
    for h in scan(src, own):
        res = ping(h, src, cfg.get("code"))
        if res:
            candidates.append((h, res))
    if not candidates:
        return None

    # где код уже подходит — сразу берём
    ready = [(h, r[1]) for h, r in candidates if r[0] == 200]
    if len(ready) == 1:
        return ready[0]

    if len(candidates) > 1:
        print("Нашёл несколько устройств:")
        for i, (h, _) in enumerate(candidates, 1):
            print(f"  {i}. {h}")
        choice = input("Номер: ").strip()
        if not choice.isdigit() or not 1 <= int(choice) <= len(candidates):
            return None
        h, res = candidates[int(choice) - 1]
    else:
        h, res = candidates[0]

    while res and res[0] == 403:
        code = input(f"Код с экрана приёма на телефоне ({h}): ").strip()
        res = ping(h, src, code)
        if res and res[0] == 200:
            cfg["code"] = code
        elif res and res[0] == 403:
            print("Неверный код, ещё раз.")
    if not res or res[0] != 200:
        return None
    return h, res[1]


def collect(args):
    files = []
    for a in args:
        p = Path(a)
        if p.is_dir():
            files += sorted(f for f in p.rglob("*") if f.is_file())
        elif p.is_file():
            files.append(p)
    return files


def upload(host, src, code, path):
    size = path.stat().st_size
    conn = connect(host, src, timeout=60)
    conn.putrequest("POST", "/upload")
    conn.putheader("X-Code", code)
    conn.putheader("X-Filename", urllib.parse.quote(path.name))
    conn.putheader("Content-Type", "application/octet-stream")
    conn.putheader("Content-Length", str(size))
    conn.endheaders()
    sent = 0
    start = time.time()
    with open(path, "rb") as f:
        while chunk := f.read(256 * 1024):
            conn.send(chunk)
            sent += len(chunk)
            speed = sent / max(time.time() - start, 0.001) / 1024 / 1024
            print(f"\r  {path.name}: {sent * 100 // max(size, 1)}%  ({speed:.1f} МБ/с)   ", end="", flush=True)
    resp = conn.getresponse()
    resp.read()
    conn.close()
    print()
    return resp.status == 200


def upload_relay(key, path):
    """Через трубу ppng.io, зашифровано тем же шифром, что и в приложении (Relay.kt)."""
    size = path.stat().st_size
    iv = os.urandom(16)
    cipher = RelayCipher(key, iv)
    header = json.dumps({"name": path.name, "size": size}).encode("utf-8")
    url = urllib.parse.urlsplit(relay_url(key))
    # без привязки к Wi-Fi: в интернет пусть идёт обычным путём, в том числе через VPN
    conn = http.client.HTTPSConnection(url.netloc, timeout=30)
    conn.putrequest("PUT", url.path)
    conn.putheader("Content-Type", "application/octet-stream")
    conn.putheader("Content-Length", str(16 + 4 + len(header) + size + 32))
    conn.endheaders()
    conn.send(iv)
    conn.send(cipher.encrypt(len(header).to_bytes(4, "big") + header))
    sent = 0
    start = time.time()
    with open(path, "rb") as f:
        while chunk := f.read(256 * 1024):
            # если телефон не слушает, труба перестаёт брать байты и send упадёт по таймауту
            conn.send(cipher.encrypt(chunk))
            sent += len(chunk)
            speed = sent / max(time.time() - start, 0.001) / 1024 / 1024
            print(f"\r  {path.name}: {sent * 100 // max(size, 1)}%  ({speed:.2f} МБ/с)   ", end="", flush=True)
    conn.send(cipher.tag())
    reply = conn.getresponse().read().decode("utf-8", "replace")
    conn.close()
    print()
    # труба отвечает сразу, а «received successfully» дописывает, когда телефон всё забрал
    return "received successfully" in reply


def send_relay(cfg, files):
    print(f"В Wi-Fi телефона нет — отправляю через интернет: {len(files)} шт.")
    print("(на телефоне должен быть включён «Приём»)")
    clawd_event("send_start", files[0].name if len(files) == 1 else f"{len(files)} файлов")
    ok = 0
    for path in files:
        try:
            if upload_relay(cfg["relay_key"], path):
                ok += 1
            else:
                print(f"  {path.name}: телефон не принял")
        except (OSError, http.client.HTTPException) as e:
            print(f"\n  {path.name}: телефон не отвечает ({e})")
    clawd_event("send_done", "ok" if ok else "fail")
    print(f"Готово: {ok} из {len(files)}. На телефоне смотри «Загрузки/С телефона».")


def pick_files():
    """Окно выбора файлов, если запустили просто двойным кликом."""
    import tkinter
    from tkinter import filedialog

    root = tkinter.Tk()
    root.withdraw()
    root.attributes("-topmost", True)
    names = filedialog.askopenfilenames(title="Что отправить на телефон?")
    root.destroy()
    return list(names)


def main():
    files = collect(sys.argv[1:] or pick_files())
    if not files:
        print("Ничего не выбрано.")
        return

    cfg = load_config()
    ips = local_ips()
    target = find_target(cfg, ips[0], set(ips)) if ips else None
    if not target:
        if cfg.get("relay_key"):
            return send_relay(cfg, files)
        print("Телефон не найден. На телефоне открой PhotoSync и включи «Приём» (оба в одном Wi-Fi).")
        print("После первой отправки по Wi-Fi можно будет слать и через интернет.")
        return
    src = ips[0]
    host, name = target
    cfg["host"] = host
    if host in LAST_RELAY_KEY:
        cfg["relay_key"] = LAST_RELAY_KEY[host]
    save_config(cfg)

    print(f"Отправляю на {name} ({host}): {len(files)} шт.")
    clawd_event("send_start", files[0].name if len(files) == 1 else f"{len(files)} файлов")
    ok = 0
    for path in files:
        try:
            if upload(host, src, cfg["code"], path):
                ok += 1
            else:
                print(f"  {path.name}: телефон не смог сохранить")
        except OSError as e:
            print(f"\n  {path.name}: ошибка связи ({e})")
    clawd_event("send_done", "ok" if ok else "fail")
    print(f"Готово: {ok} из {len(files)}. На телефоне смотри «Загрузки/С телефона».")


if __name__ == "__main__":
    try:
        main()
    finally:
        input("\nНажми Enter, чтобы закрыть...")
