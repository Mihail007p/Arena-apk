# Arena — Android WebView wrapper

> **TL;DR (EN):** A tiny, dependency-free Android app that opens `https://arena.ai/`
> full-screen in a dark-themed WebView. No Gradle, no AndroidX, no Kotlin — just the
> Android framework, `aapt2`/`d8`/`apksigner` and one Java file. Built and signed by
> `./build.sh`.

Небольшое Android-приложение-обёртка для **arena.ai**: полноэкранный WebView,
всегда тёмная тема сайта, адаптивная космическая иконка, оффлайн-заглушка и
корректная обработка кнопки «Назад».

**Это не официальное приложение arena.ai и не проект Are.na.** Репозиторий не
аффилирован с Arena; это самодельный лаунчер, собранный без Android Studio.

| | |
|---|---|
| Пакет | `ai.arena.app` |
| Версия | **2.0** (`versionCode 11`) |
| minSdk / targetSdk | 23 / 33 (Android 6.0 … 13, проверено на Tecno Pova Neo 3 / HiOS 13) |
| Размер APK | ~474 КБ |
| Зависимости | **нет** — только Android framework |
| Подпись | самоподписанная, v1 + v2 |

## Что внутри

- `MainActivity.java` — один класс: WebView, тёмная тема через
  `localStorage.theme='dark'` + класс `html.dark`, оффлайн-карточка с кнопками
  «Повторить» / «Настройки сети», файловый chooser, разрешения на камеру/микрофон,
  обработка Back (одинарное — назад по истории, двойное — меню).
- Полный экран: **никакого тулбара, адресной строки или прогресс-полоски** — сайт
  занимает весь экран.
- Адаптивные иконки (`mipmap-anydpi-v26`) + PNG для всех плотностей, сгенерированные
  из `icon-space.png` скриптом `make_icons.py`.
- `check_js.py` — проверяет синтаксис всех JS, которые внедряются в WebView, через
  `node --check`. Сборка **падает**, если JS битый (это уберегло от одного бага в v1.4).
- Нет интернета → тёмная заглушка вместо «серого экрана».

## Требования

- Linux или macOS, `bash`, `curl`, `unzip`, `zip`
- **JDK 11+** (`javac`, `keytool`)
- **Python 3.9+** с Pillow (`pip install Pillow`) — для `make_icons.py`
- **Node.js** — для `check_js.py` (`node --check`)
- ~120 МБ свободного места и интернет при первом запуске: `build.sh` сам скачает
  Android **build-tools r33.0.2** и **platform android-33-ext5** в `/tmp/arena-sdk`.

> Если SDK уже установлен: `ARENA_SDK=/path/to/sdk ./build.sh` (ожидается
> `$ARENA_SDK/bt/android-13/aapt2` и
> `$ARENA_SDK/platform/android-33-ext5/android.jar`).

## Сборка

```bash
git clone <your-repo-url>
cd Arena-android
chmod +x build.sh
./build.sh
```

Результат: `Arena.apk` и `Arena.apk.sha256.txt` в корне проекта.

Переменные окружения (необязательные):

| Переменная | По умолчанию | Назначение |
|---|---|---|
| `ARENA_SDK` | `/tmp/arena-sdk` | каталог с build-tools и android.jar |
| `ARENA_KEYTOOL` | `keytool` из `PATH` | путь к `keytool` |
| `ARENA_STORE_PASS` | `arena2026` | пароль keystore |
| `ARENA_KEY_PASS` | `arena2026` | пароль ключа |
| `ARENA_KEY_ALIAS` | `arena` | алиас ключа |

**Воспроизводимость:** сборка из чистого репозитория с тем же ключом даёт APK,
у которого совпадают все 20 внутренних файлов (CRC), версия, сертификат и размер.
Побайтовый SHA-256 может отличаться: `zip`/`aapt2` пишут временные метки, а
`SOURCE_DATE_EPOCH` здесь не задаётся.

Проверить подпись:

```bash
$ARENA_SDK/bt/android-13/apksigner verify --verbose --print-certs Arena.apk
$ARENA_SDK/bt/android-13/aapt dump badging Arena.apk
```

## Установка

1. Скачайте `Arena.apk` (например, из **GitHub Releases**).
2. На телефоне разрешите установку из неизвестных источников для этого источника.
3. Откройте APK или выполните `adb install -r Arena.apk`.

Обновления ставятся **поверх** без потери сессии, только если подписаны **тем же
ключом**. Первый запуск `build.sh` создаёт `arena-release.jks`; сохраните его
надёжно и не публикуйте.

## ⚠️ Безопасность ключа подписи

- `arena-release.jks` **не входит в репозиторий** и исключён через `.gitignore`.
- Пароль по умолчанию `arena2026` — для реальных релизов задайте свой через
  `ARENA_STORE_PASS` / `ARENA_KEY_PASS`.
- Если выложить приватный ключ в публичный репозиторий, любой сможет подписывать
  APK от имени вашего приложения. Не делайте этого.

## Структура

```
AndroidManifest.xml          пакет, версии, permissions, adaptive icon
build.sh                     8 шагов сборки + проверка подписи
check_js.py                  node --check для внедряемого JS
make_icons.py               генерация PNG иконок из icon-space.png
src/ai/arena/app/            MainActivity.java
res/                         values, values-ru, mipmap-*, drawable, adaptive XML
icon-space.png               исходный космический арт (1024×1024)
arena-icon-512.png           превью иконки
Arena-space-art.png          очищенный арт без белого ореола
ui-preview.html              статичный макет интерфейса
tools/                       опциональные утилиты (страница загрузки, локальный сервер)
docs/INSTALL.ru.md           подробная инструкция по установке
docs/EXPERIMENT.ru.md        история эксперимента с селектором моделей ⚡A
```

## Известные ограничения

- Приложение **самоподписанное**: нет автоматических обновлений из Play Market.
- Нужен свежий **Android System WebView / Chrome** и интернет.
- Вход через **Google / Apple OAuth внутри WebView может быть заблокирован** —
  используйте вход по e-mail или откройте сайт в Chrome («Открыть в браузере» в меню).
- Тёмная тема — это штатная тема сайта, а не инверсия CSS и не `FORCE_DARK`.

## История версий

| Версия | Что было |
|---|---|
| 1.0 | первый WebView-лаунчер |
| 1.2 | полный экран без тулбара, оптимизация жизненного цикла |
| 1.3 | космическая адаптивная иконка |
| 1.4–1.9 | эксперимент с активацией селектора моделей ⚡A → [итог](docs/EXPERIMENT.ru.md) |
| **2.0** | эксперимент полностью вырезан, чистый финальный релиз |

Архив `Arena-github.zip` (вне репозитория) — готовая к загрузке копия этой папки
без ключа и собранных бинарников.

## Лицензия

Лицензия **не выбрана** — добавьте свой `LICENSE` перед публикацией (MIT, Apache-2.0
и т.д.). Обратите внимание: иконка сгенерирована ИИ, поэтому для коммерческого
использования отдельно проверьте условия используемого генератора и права на
товарный знак Arena.
