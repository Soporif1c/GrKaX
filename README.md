# GrKa X

Самостоятельный Android-клиент на ядре **Xray** (стабильная сборка) с поддержкой
современных транспортов, включая **XHTTP**. Написан с нуля на Kotlin +
Jetpack Compose. Не форк — собственная кодовая база.

<p align="center">
  <em>VLESS · VMess · Trojan · Shadowsocks — TCP / WS / gRPC · HTTPUpgrade · <b>XHTTP</b> · REALITY / TLS</em>
</p>

## Возможности

- **Ядро Xray** через `libv2ray` (AndroidLibXrayLite, стабильный тег `v26.6.27`,
  Xray-core 25.x). Туннелирование через `hev-socks5-tunnel` — тот же надёжный
  стек, что и в проверенных клиентах.
- **XHTTP / SplitHTTP** — полноценный `xhttpSettings` (`mode`, `host`, `path`,
  `extra`) в генераторе конфига и в парсере ссылок.
- **Протоколы**: VLESS, VMess, Trojan, Shadowsocks.
- **Транспорты**: TCP (+http header), WS, gRPC, HTTPUpgrade, XHTTP, mKCP, H2.
- **Безопасность**: TLS и REALITY (pbk/sid/spx/fp/flow).
- **Подписки** с заголовком `x-hwid` — работает с панелями, где включён лимит
  устройств (например, **Remnawave**). Показывает остаток трафика и срок из
  `subscription-userinfo`.
- **Импорт**: из буфера обмена, ручная вставка, base64-подписки. Экспорт: QR и
  копирование ссылки.
- **Пинг** каждого сервера (реальная задержка через ядро) и активного соединения.
- **Маршрутизация**: глобально / обход локальной сети / обход РФ (geosite+geoip).
- **3 темы оформления**: Aurora (тёмно-синяя), Ocean (бирюзовая), Pearl (светлая).
- Локализация **RU / EN**, автозапуск при загрузке, статистика скорости и объёма.

## Почему интернет не пропадает

Ошибка «VPN подключён, но интернета нет» из прошлого клиента здесь закрыта
несколькими мерами:

1. На TUN-интерфейс всегда добавляется хотя бы один DNS-сервер — без этого
   резолвинг умирает.
2. Своё приложение всегда исключается из туннеля
   (`addDisallowedApplication`), поэтому прямые (direct) соединения ядра не
   зацикливаются в VPN.
3. `setUnderlyingNetworks` через `NetworkCallback` — трафик ядра идёт по
   реальной сети даже при переключении Wi-Fi/LTE.
4. Аккуратная последовательность остановки (stopSelf → пауза → close) —
   не остаётся «висящего» интерфейса и занятого порта.
5. Geo-файлы (`geoip.dat`, `geosite.dat`) упакованы в APK и копируются в
   приватную папку, поэтому правила маршрутизации не роняют старт ядра.

## Сборка

Сборка идёт в GitHub Actions (`.github/workflows/build.yml`):

1. Скачивается стабильный `libv2ray.aar` (Xray-core).
2. `hev-socks5-tunnel` компилируется под все ABI через NDK.
3. Скачиваются `geoip.dat` / `geosite.dat`.
4. Gradle собирает подписанные release-APK (arm64, armeabi-v7a, x86, x86_64,
   universal).

Артефакты появляются во вкладке **Actions**. Чтобы выпустить релиз — запустите
workflow вручную (**Run workflow**) и укажите `release_tag`.

### Секреты репозитория

| Секрет | Назначение |
|---|---|
| `APP_KEYSTORE_BASE64` | keystore (`.jks`) в base64 |
| `APP_KEYSTORE_PASSWORD` | пароль keystore |
| `APP_KEYSTORE_ALIAS` | алиас ключа |
| `APP_KEY_PASSWORD` | пароль ключа |

## Лицензия

GPL-3.0. Использует [Xray-core](https://github.com/XTLS/Xray-core),
[AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite) и
[hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel).
