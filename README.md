# Big Head VPN

Минимальный Android VPN-клиент с красивым простым интерфейсом и архитектурой под расширение протоколов.

## Что уже есть

- Android-приложение на Kotlin без тяжёлого UI-фреймворка.
- Импорт профилей по URI: `vless://`, `hysteria://`, `hysteria2://`, `hy2://`.
- Быстрая вставка профилей из буфера обмена.
- Список добавленных серверов с выбором активного сервера.
- `VpnService` и Android per-app VPN через `VpnService.Builder.addAllowedApplication`.
- Красивый выбор приложений, для которых должен работать VPN. Если список пустой, VPN применяется ко всем приложениям.
- Лёгкий протокольный engine: Android TUN через `hev-socks5-tunnel`, VLESS через Xray-core и Hysteria2 через официальный core.
- Проверка VLESS при старте через реальный outbound SOCKS CONNECT.
- Проверка и сортировка серверов по пингу.
- Отображение времени работы VPN.
- Встроенное окно логов для диагностики.
- Foreground-уведомление в шторке с кнопкой выключения VPN.
- Quick Settings tile для включения/выключения VPN рядом с фонариком и автоповоротом.

## Важное ограничение текущего MVP

`sing-box/libbox` удалён из сборки: он оказался слишком тяжёлым для целей этого MVP и тянул лишнюю внутреннюю инфраструктуру. Вместо него используется маленький `hev-socks5-tunnel` для TUN-to-SOCKS, Xray-core для VLESS и отдельный Hysteria2 binary как native-lib.

Сейчас подключены:

- VLESS через Xray-core на `arm64-v8a` и `x86_64`: TLS, Reality, WebSocket, gRPC, XHTTP/SplitHTTP и HTTPUpgrade зависят от параметров импортированного URI.
- Fallback VLESS на Kotlin для ABI без Xray: базовые `tcp`/`raw` и `ws` transport с обычным TLS.
- Hysteria2 через официальный Android core `app/v2.9.2` в режиме локального SOCKS5.

Сборка сейчас создаёт один universal debug APK:

- `app/build/outputs/apk/debug/app-debug.apk`

Точка подключения находится здесь:

- `app/src/main/java/app/bighead/vpn/vpn/LightweightTunnelEngine.kt`
- `app/src/main/java/app/bighead/vpn/vpn/Hysteria2Client.kt`
- `app/src/main/java/app/bighead/vpn/vpn/XrayClient.kt`
- `app/src/main/java/app/bighead/vpn/vpn/Socks5Server.kt`
- `app/src/main/java/app/bighead/vpn/vpn/VlessOutbound.kt`
- `app/src/main/java/app/bighead/vpn/vpn/BigHeadVpnService.kt`

## Сборка без Android Studio

Проект можно вести из VS Code. Android Studio не обязательна.

Расширения Gradle for Java и Kotlin Language дают подсветку, навигацию и Gradle-панель, но сами по себе не ставят Android SDK. Для сборки нужны:

- Android SDK с `platforms;android-35`, `build-tools` и `platform-tools`.
- Переменная `ANDROID_HOME`, указывающая на Android SDK.
- Gradle wrapper `./gradlew` или установленный системный `gradle`.

После этого можно запускать задачу `Android: assemble debug` из VS Code.

На Android 13+ разреши приложению уведомления, иначе карточка VPN в шторке может быть скрыта. Плитку можно добавить через редактирование быстрых настроек в системной шторке.

В этом проекте уже подготовлены:

- локальный Android SDK в `.android-sdk`;
- Gradle wrapper `./gradlew`;

Минимальный путь:

1. Скачать Android Command Line Tools с https://developer.android.com/studio#command-tools
2. Распаковать в `$HOME/Android/Sdk/cmdline-tools/latest`.
3. Добавить в shell:

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
```

4. Установить нужные пакеты:

```bash
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
sdkmanager --licenses
```

5. Собрать APK:

```bash
env -u HTTP_PROXY -u HTTPS_PROXY -u http_proxy -u https_proxy \
  ANDROID_HOME="$PWD/.android-sdk" \
  GRADLE_USER_HOME="$PWD/.gradle" \
  ./gradlew assembleDebug
```

Если `./gradlew` ещё нет, его можно сгенерировать один раз установленным Gradle:

```bash
gradle wrapper
```

## iOS позже

На iOS VPN-клиент обычно делается через `NetworkExtension` (`NEPacketTunnelProvider`). Это возможно, но для App Store часто нужны entitlement/approval от Apple. Поэтому текущая структура держит профили и протоколы отдельно от Android `VpnService`, чтобы позже вынести общий профильный слой и сделать отдельный iOS tunnel provider.
