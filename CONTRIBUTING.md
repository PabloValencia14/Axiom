# Contribuir a Axiom

Gracias por contribuir. Este proyecto se distribuye bajo GNU AGPL v3 o posterior; los cambios deben mantener los avisos de copyright/licencia de terceros y no retirar atribuciones.

## Ramas y cambios

- Crea una rama descriptiva desde la rama principal actual: `feat/...`, `fix/...`, `docs/...` o `test/...`.
- Mantén cada PR centrada en un cambio y evita mezclar formato masivo con cambios funcionales.
- No incluyas `local.properties`, claves, tokens, configuraciones privadas de Google, datos de biblioteca, libros, volcados, capturas, APKs ni artefactos generados.

## Comprobaciones

Antes de abrir el PR, ejecuta lo que corresponda al cambio:

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
./gradlew assembleDebugAndroidTest
```

Para ejecutar pruebas instrumentadas, selecciona y autoriza explícitamente el número de serie (`SERIAL`) del emulador o dispositivo que quieras usar. No enumeres dispositivos ni uses tareas Gradle `connected...` o `install...`, que podrían dirigirse a un dispositivo distinto. Después de compilar los APKs con los comandos anteriores, instálalos y ejecuta las pruebas con:

```bash
adb -s SERIAL install app/build/outputs/apk/debug/app-debug.apk
adb -s SERIAL install app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s SERIAL shell am instrument -w org.readera.openreadera.test/androidx.test.runner.AndroidJUnitRunner
```
Incluye el resultado de las comprobaciones y explica cualquier prueba omitida. Cambios de persistencia deben conservar/actualizar los esquemas de Room y sus pruebas; cambios nativos requieren revisar NDK/CMake y cobertura de conversión.

## Pull requests

Abre un PR hacia la rama principal. Describe motivo, alcance, comportamiento observable, pruebas y riesgos; enlaza la incidencia relacionada. Para cambios de UI añade capturas solo si son sintéticas y no contienen documentos, cuentas ni datos personales. Espera revisión y CI verde antes de integrar. No publiques tags de versión desde un PR.

## Informes de seguridad y privacidad

No publiques vulnerabilidades, claves, libros, capturas con información personal ni bases de datos en incidencias o PR públicos. Para vulnerabilidades usa la función **Report a vulnerability** de GitHub Security Advisories del repositorio (reporte privado); si no está habilitada, contacta al mantenedor mediante el correo de seguridad indicado en el perfil del repositorio, sin adjuntar datos de usuarios. Nunca solicites ni compartas credenciales en una incidencia.
