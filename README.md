# ServerDoctor v1.1.2

Утилита диагностики для **Paper 1.21.11**, **Java 21+**. Сервер собирает метрики и предоставляет локальные отчёты без обязательных аккаунтов и внешних API.

## v1.1.2 · Observatory live dashboard refresh

The **web monitoring UI** in `src/main/resources/dashboard.html` is now redesigned independently of the offline HTML report template. Changes include a new desktop and mobile layout, interactive TPS/MSPT/heap history switching, status and memory displays, incident feed, heatmap visualization, scan history and a redesigned token login. This is the actual page embedded into the plugin JAR, not a standalone mockup.

**To see the new interface:** run `BUILD.cmd`, replace the old `ServerDoctor-*.jar` in your server `plugins/` folder (leave only one ServerDoctor version), fully **stop and restart** the Paper server, then reload `http://127.0.0.1:8765/`. `/doctor reload` does not replace the HTML bundled into the old JAR. The footer and sidebar must show `v1.1.2`.

The web server API (`/api/status`, `/api/history`, `/api/chunks`) and token authentication are unchanged; all resources are local and the dashboard does not download external scripts or fonts. The separate offline scan-report design from v1.1.1 remains in place.

## Сборка на Windows

1. Распакуйте архив **без изменения внутренней структуры**.
2. Установите JDK 21 или более новый (проверьте `javac -version`).
3. Запустите **BUILD.cmd**. Требуется интернет для скачивания зависимостей при первой сборке. Если Maven отсутствует, скрипт попытается установить его в `.build-tools`, проверив официальную контрольную сумму SHA-512.
4. После сообщения `BUILD SUCCESS` установите `target/ServerDoctor-v1.1.2.jar` в `plugins/` Paper-сервера и перезапустите сервер.
5. Выполните `/doctor scan`. Результат появится в `plugins/ServerDoctor/reports/`.

Альтернатива: `mvn -B -ntp clean verify`. Если есть проблема, проверьте `build.log` и `target/surefire-reports`.

**Проверка:** офлайн-компиляция с интерфейсными заменителями Paper и 11 локальных тестов прошла. Полноценная сборка с реальным Paper API и проверка загрузки плагина на Paper-сервере в этом окружении **не проведены**. JAR с тестовыми заменителями в релиз намеренно не включён. При любых ошибках сборки используйте `build.log`.

## Основные функции

- **Lag Spike Detector:** постоянный сбор реальных длительностей последних тиков Paper (кольцевой буфер) раз в ~1 секунду; запись событий выше порога, ограничение частоты событий и уведомления.
- **Chunk Heatmap:** `/doctor chunks` вручную сканирует **только загруженные** чанки, не подгружает новые. Максимальное количество и обработка за тик ограничены. Показатель `densityScore` использует сущности, блок-сущности и воронки, **не измеряет CPU конкретного чанка**. Результаты входят в последующие отчёты и веб-панель. Если секция сущностей не загружена, счётчик отмечается как недоступный.
- **Spark integration:** `/doctor spark start` и `/doctor spark stop` запускают встроенный в Paper профилировщик spark через его команды. Ссылка на профиль приходит **от spark в консоль сервера**. ServerDoctor не имеет доступа к внутренним трассам spark и не приписывает процент CPU каждому плагину самостоятельно.
- **Memory & GC Analyzer:** heap, non-heap и статистика сборщика мусора через Java MXBeans, обнаружение длительных GC-пауз между последовательными пробами. Суммарные счётчики GC означают **время жизни JVM**, не только длительность скана.
- **Smart Alerts:** уведомления OP/владельцам права `serverdoctor.alerts` и в консоль, индивидуальные ограничения частоты; необязательная доставка через Discord webhook по HTTPS (отключена по умолчанию).
- **Live Web Dashboard:** веб-панель, токен доступа, графики, история сканов, лаги, плотность чанков. По умолчанию отключена и всегда привязана к localhost: **127.0.0.1**, даже при неправильной публичной конфигурации. Все API доступны **только с Bearer-токеном**. Внешний доступ возможен через SSH-туннель или свой TLS reverse proxy с дополнительной защитой.
- **Scheduled Diagnostics:** сканирование по интервалу из `config.yml`, без наложения проверок; отключено по умолчанию. При включённой опции `scheduled.prune-old-reports` автоматическая ротация удаляет старые сгенерированные `.html`, `.json`, `.csv`, которых больше нет в индексе истории (до `scan.max-history`). До включения сделайте резервную копию нужных отчётов.
- **Advanced Reports:** полностью переработанный автономный HTML в оформлении Infrastructure Intelligence: адаптивная панель, Health Index, интерактивные графики с наведением и навигацией стрелками, анализ миров, рекомендации, GC, история лагов и карта плотности чанков; JSON для автоматизации; CSV для графиков и таблиц. Все три файла генерируются после каждого скана.

## Команды

| Команда | Назначение |
|---|---|
| `/doctor scan` | Ручное сканирование TPS/MSPT/CPU/RAM и создание HTML/JSON/CSV |
| `/doctor status` | Прогресс сканирования и текущие метрики |
| `/doctor latest` | Последняя запись |
| `/doctor history [page]` | История диагностики |
| `/doctor report [id|latest]` | Названия файлов отчёта |
| `/doctor chunks` | Запуск обследования только загруженных чанков |
| `/doctor spark start` | Запуск профилировщика Paper spark с временем из конфига |
| `/doctor spark stop` | Остановка профилировщика spark |
| `/doctor web` | Статус и локальный адрес веб-панели |
| `/doctor schedule` | Настройки запланированных диагностик |
| `/doctor reload` | Перезагрузка config.yml и сервисов |

Основное разрешение `serverdoctor.admin` (по умолчанию OP). Уведомления получают пользователи с `serverdoctor.alerts`.

## Конфигурация и приватность

Настройки находятся в `plugins/ServerDoctor/config.yml` после первого запуска. Важные параметры:

- `monitor.enabled`, `monitor.spike-threshold-ms`, `monitor.gc-pause-threshold-ms`.
- `chunks.max-chunks`, `chunks.per-tick`, `chunks.top-results`.
- `alerts.enabled`, `alerts.in-game`, `alerts.discord.enabled`, `alerts.discord.webhook-url`.
- `scheduled.enabled`, `scheduled.interval-minutes`, `scheduled.prune-old-reports`.
- `web.enabled`, `web.bind`, `web.port`, `web.token`.
- `privacy.include-plugin-list` (по умолчанию `false`).

Для веб-панели установите `web.enabled: true`, выполните `/doctor reload`, откройте **http://127.0.0.1:8765** на машине сервера и введите токен из config.yml. Если токен пуст, он создаётся автоматически и сохраняется в конфиге. **Не публикуйте токен и не запускайте панель через прямой HTTP в интернете**. Система принудительно ограничивает адрес прослушивания loopback.

HTML/JSON/CSV сохраняются в `plugins/ServerDoctor/reports/`. JSON содержит техническую информацию, координаты и названия миров. При включённом `privacy.include-plugin-list` добавляются версии плагинов в HTML. Отчёт стоит проверить перед передачей третьим лицам. При включённом Discord webhook наружу отправляются только короткие сообщения об аномалиях, без мировых координат и названий игроков.

## Технические ограничения

- Данные TPS от Paper являются скользящими средними, а не TPS каждого тика.
- `Bukkit.getTickTimes()` содержит недавние длительности тиков, но по ним нельзя доказать, какой плагин вызвал лаг. Для этого используется spark.
- Chunk density не равнозначна измеренному времени обработки чанка. Выборка ограничена конфигом и может не покрывать весь сервер.
- Нельзя безопасно вызывать Bukkit world API из фоновых HTTP-потоков: веб-панель читает **только подготовленные неизменяемые снимки**. Доступ к миру выполняется только на главном потоке, маленькими порциями.
- Профиль Spark анализируется в интерфейсе spark. Автоматической загрузки и разбора spark URL внутри ServerDoctor пока нет.
- Состояние мониторинга хранится в памяти и сбрасывается при перезапуске. История сканирований на диске сохраняется.
- Текущее решение рассчитано на классические Paper-серверы, не заявляет поддержку Folia.

Лицензия MIT.

## Изменения v1.1.2

- Переработан **встроенный HTML-генератор**, а не только демонстрационный файл: любой новый `/doctor scan` создаёт отчёт с новым дизайном.
- Новая визуальная система: крупная диагностическая сводка, круговой Health Index, метрики, графики и шесть разделов аналитики.
- Графики TPS/MSPT поддерживают отображение конкретной пробы при наведении и перемещение по пробам стрелками при фокусе.
- Отчёт полностью автономен: локальные CSS, JavaScript и SVG; никакие CDN и сетевые запросы не требуются.
- Пересчитан прогресс-круг для нового SVG-радиуса, сохранено экранирование пользовательских значений.
- Исправлены имена выходного JAR и сообщения в скриптах сборки для v1.1.2.
- Версия веб-панели обновлена в метаданных; функциональность её не менялась.

**Важно:** изменения применяются к новым HTML-отчётам. Уже сгенерированные файлы останутся прежними.
