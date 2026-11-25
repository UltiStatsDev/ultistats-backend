# Импорт новых команд и игроков из CSV

`POST /api/v1/imports/teams-players/csv` принимает ровно две файловые части
`applications` и `rosters` (multipart/form-data). Успех: 201 после commit.
Нужна действующая сессия с `SPORTS_WRITE`, штатный CSRF и три заголовка
захваченного контекста установки/аккаунта/сессии. Права повторно проверяются
внутри транзакции под штатным authority fence.

Маршрут выключен по умолчанию: `ultistats.csv-import.enabled=false`
(`ULTISTATS_CSV_IMPORT_ENABLED`). При выключенном флаге авторизованный запрос
получает 404. Это документация конфигурации, не включение действующего окружения.

Синтетический пример applications.csv:

```csv
Team Title,City,status
Falcons,North,rejected
```

Синтетический пример rosters.csv:

```csv
Player First Name,Player Last Name,Team,Jersey Number,role
Alex,Stone,Falcons,0,coach
Sam,Reed,Falcons,,player
```

Заголовки обязательны, порядок произвольный; дубликат используемого заголовка
ошибочен. Остальные колонки игнорируются, status/role ничего не фильтруют.
UTF-8, необязательный BOM, запятая, LF/CRLF, стандартные кавычки CSV, включая
переводы строк и запятые внутри кавычек, `""` для кавычки. Пустые строки пропускаются.
Нужны непустые Team Title, Player First Name, Player Last Name после trim
(до 255 символов); City пустой → null, иначе до 127. Team точно совпадает
с Team Title этого пакета после trim, регистр сохраняется. Отсутствующая или
неоднозначная ссылка отклоняет пакет. Пустой Jersey Number → null; иначе
десятичное целое 0…2147483647. Состав с одними заголовками допустим.

Каждая строка создаёт новый UUID. Повтор имён, существующие имена в БД или
повтор всего запроса не объединяют и не обновляют записи. Дубли названий
команд допустимы без ссылок; ссылка на несколько строк с одним названием
ошибочна. Все проверки проходят до записи, сохранение атомарно.

После штатного входа и при необходимости смены временного пароля получите
свежий CSRF через `/api/v1/auth/csrf`. Используйте cookie jar своей сессии и
значения `installationId`, `account.id`, `sessionId` из захваченного auth-контекста
(см. [контракт auth](auth-contract.md)). Следующий curl использует переменные
своего контекста; все CSV-значения выше вымышлены:

```bash
curl --fail-with-body -b "$COOKIE_JAR" \
  -H "X-CSRF-TOKEN: $CSRF_TOKEN" \
  -H "X-UltiStats-Installation-Id: $INSTALLATION_ID" \
  -H "X-UltiStats-Account-Id: $ACCOUNT_ID" \
  -H "X-UltiStats-Session-Id: $SESSION_ID" \
  -F 'applications=@applications.csv;type=text/csv' \
  -F 'rosters=@rosters.csv;type=text/csv' \
  "$BASE_URL/api/v1/imports/teams-players/csv"
```

Ответ содержит `counts:{teams,players,memberships}`, `teams:[{applicationRow,teamId}]`,
`players:[{rosterRow,playerId}]`, `memberships:[{rosterRow,teamId,playerId,number}]`.
`row` — номер логической CSV-записи (заголовок = 1), многострочное поле остаётся
одной записью. Новые UUID можно прочитать обычными GET команд/игроков/членств.

Ошибки валидации: 400 `application/problem+json`,
`errors:[{file,row,column,code}]`; для файловой ошибки row/column могут быть null.
Ответ не содержит загруженных значений. Коды включают `INVALID_UTF8`,
`INVALID_CSV`, `HEADER_REQUIRED_OR_DUPLICATE`, `REQUIRED`, `TOO_LONG`,
`INVALID_NUMBER`, `TEAM_NOT_FOUND`, `TEAM_AMBIGUOUS`, `MULTIPART_PARTS`.
Лимиты: 1 MiB каждого файла, 2 MiB файловых данных вместе, 200 команд,
5000 строк состава; превышение → 413 без записи. Глобальные multipart-лимиты
приложения не увеличиваются. Ошибка сохранения откатывает весь пакет.
Обычные ответы auth/CSRF (401/403) сохраняются.

Для защиты значений при ошибке PostgreSQL настроено
`spring.datasource.hikari.data-source-properties.logServerErrorDetail=false`.
Это убирает подробности серверных ошибок из JDBC exceptions и журналов,
не меняет SQL, транзакции или права. Не переопределяйте это свойство в
`DATABASE_URL` значением true: URL имеет приоритет над свойствами datasource.
