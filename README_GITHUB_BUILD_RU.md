# Облачная сборка APK через GitHub Actions

1. Создайте на GitHub новый **публичный** или приватный репозиторий (например, `iphone-android-touch`). Не добавляйте README, лицензию или .gitignore при создании.
2. Распакуйте этот архив на компьютере.
3. В репозитории нажмите **Add file → Upload files** и перетащите содержимое распакованной папки (включая папку `.github`, `app`, `gradle`, а также файлы `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`). Важно: загрузите содержимое папки, а не сам ZIP.
4. Нажмите **Commit changes**.
5. Откройте вкладку **Actions**. Выберите workflow **Build Android APK** и нажмите **Run workflow**, если он не запустился автоматически.
6. Дождитесь зелёной галочки. Откройте завершённый запуск, прокрутите вниз до **Artifacts** и скачайте `iPhoneAndroidTouch-debug-apk.zip`.
7. Распакуйте скачанный ZIP: внутри будет `app-debug.apk`.

Сборка выполняется на серверах GitHub, поэтому на Windows не нужны Java, Android Studio или Gradle. Это только способ сборки исходников; APK ещё нужно проверить на устройстве.
