# Axiom

Axiom es una aplicación Android de código abierto para leer y anotar documentos. Incluye biblioteca y progreso locales; lector de EPUB, TXT/Markdown, FB2, DOCX y PDF; cómics CBZ; conversión local de MOBI/AZW/AZW3 sin DRM a EPUB; búsqueda, marcadores, notas y escritura con stylus. En PDF incluye edición no destructiva de páginas, OCR/copia buscable y exportación de páginas a imagen o texto. Las firmas visuales de PDF son anotaciones, no firmas criptográficas.

## OCR y privacidad

El OCR y la generación de capas de texto PDF usan Google ML Kit Text Recognition con modelo latino incluido. No se anuncia reconocimiento de alfabetos no latinos. El escáner de documentos usa ML Kit Document Scanner y puede obtener componentes mediante Google Play Services. El OCR procesa localmente el documento seleccionado; Axiom no envía automáticamente documentos a un servidor propio.

La biblioteca, el progreso y las anotaciones se guardan en el dispositivo. Integraciones opcionales como Google Drive, catálogos en línea y servicios configurados por el usuario (por ejemplo OpenRouter) transmiten datos a esos proveedores cuando se activan; revisa sus políticas. No publiques libros, claves, bases de datos, capturas privadas ni datos personales en incidencias o pull requests.

La traducción completa, cuando se solicita, envía texto nativo de las páginas a Google Translate; no hace OCR ni incluye páginas sin texto nativo. La importación y los adjuntos de Drive bloquean rutas o datos privados de la aplicación. La autorización de Drive está ligada a la cuenta; los tokens heredados sin cuenta asociada requieren volver a autorizar.

## Funciones y límites actuales

- OCR orientado a texto latino; el reconocimiento puede no encontrar texto o cometer errores según calidad, resolución, idioma y diseño de página.
- MOBI/AZW/AZW3 requieren documentos sin DRM y conversión válida; no se elimina ni evade DRM.
- CBZ está implementado; CBR, DOC, RTF, DJV/DJVU y CHM no tienen motor de lectura en esta versión, aunque aparezcan como formatos reconocidos por la aplicación.
- La firma PDF solo dibuja una marca visual en una copia; no incorpora certificado, identidad verificada ni validación criptográfica.
- La traducción de PDF conserva texto nativo con relleno, trazo o ambos, junto con sus colores y el estado gráfico de trazo. Rechaza texto con recorte, patrones de color u otra geometría que no pueda conservar con seguridad; el original nunca se sustituye.
- La sincronización y servicios remotos dependen de credenciales, red y disponibilidad de terceros; la copia local sigue siendo independiente.
- El escaneo automático agrupa el inventario, conserva metadatos existentes, anotaciones y documentos en la papelera, y se limita a uno cada 30 segundos al reanudar la app; la actualización explícita sigue disponible de inmediato.
- La búsqueda, los filtros y el ordenamiento de la biblioteca se calculan fuera del hilo principal; solo la coincidencia de texto usa un debounce de 250 ms.
- El lector comparte una caché de imágenes de 32–128 MiB con una ventana móvil de páginas; el bloqueo de movimiento impide desplazar o ampliar con gestos sin desactivar la navegación por páginas.
- La biblioteca adapta sus cuadrículas a la pantalla. El lector ofrece opciones de tema accesibles de 48 dp, acceso funcional a Ajustes generales y controles de página fija para CBZ (sin controles de reflujo EPUB). La lectura en voz alta informa si no hay texto y Detener cancela la preparación pendiente.
- La traducción nativa admite PDF, DOCX, EPUB, FB2 y TXT; procesa texto seleccionable del documento, sin OCR, y deja intacto el original. En PDF conserva la página y sus gráficos, pero solo traduce texto visible compatible; los documentos escaneados sin texto seleccionable no se traducen. El ajuste del texto puede reducir el tamaño hasta el 85 % y el interlineado hasta el 90 % (con un mínimo de 9 pt cuando el original es mayor); si no cabe, la traducción falla en vez de publicar una página dañada. DOCX conserva su estructura y recursos, aunque puede cambiar la paginación; EPUB mantiene el paquete y sus recursos, pero se redistribuye. MOBI/AZW/AZW3 requieren conversión explícita a EPUB y también se redistribuyen.
- La traducción completa procesa todas las páginas con texto nativo compatible (sin el antiguo tope de 200), omite páginas sin texto, informa del envío de texto a Google Translate y admite cancelación; los errores no producen resultados incompletos y cada salida usa un nombre único. La fidelidad de PDF tiene límites: no equivale a OCR; texto rotado, fuentes o procedimientos de glifos Type3 no compatibles, recortes y efectos de texto complejos pueden provocar un rechazo explícito.
- Límites aplicados: documentos 256 MiB, cubiertas 8 MiB, snapshots 16 MiB y páginas de catálogo 4 MiB. EPUB: 2 MiB por miembro descomprimido, 16 MiB acumulados, 10.000 entradas ZIP/manifiesto y 2.000 referencias de spine.

## Requisitos

- JDK 17 (CI usa Temurin 17).
- Android SDK Platform 35 y Build Tools 35.0.0.
- Android NDK `27.0.12077973` y CMake 3.22.1.
- Gradle Wrapper incluido (Gradle 8.11.1); no hace falta Gradle global.

Configura SDK local con `local.properties` (no se versiona) o instala plataformas/herramientas desde Android Studio SDK Manager.

## Compilar, probar e instalar

Desde la raíz del repositorio:

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
./gradlew assembleDebugAndroidTest
```

En Windows usa `gradlew.bat`. `assembleDebugAndroidTest` crea el paquete instrumentado, incluido el fixture MOBI de prueba generado desde Gradle. Para instalar y ejecutar pruebas instrumentadas, selecciona y autoriza explícitamente el número de serie (`SERIAL`) del emulador o dispositivo que quieras usar. No enumeres dispositivos ni uses tareas Gradle `connected...` o `install...`, que podrían dirigirse a un dispositivo distinto:

```bash
adb -s SERIAL install app/build/outputs/apk/debug/app-debug.apk
adb -s SERIAL install app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s SERIAL shell am instrument -w org.readera.openreadera.test/androidx.test.runner.AndroidJUnitRunner
```

El código JNI/C++ y la conversión MOBI se compilan desde `app/src/main/cpp`; todas las fuentes necesarias, incluyendo la selección vendorizada de libmobi, están en el repositorio. No depende de archivos Gradle ni bibliotecas en el directorio padre.

## Estructura

- `app/src/main/java`: app Android, lector, motores, persistencia e integraciones.
- `app/src/main/cpp`: JNI, conversor y código vendorizado necesario.
- `app/src/test` y `app/src/androidTest`: pruebas unitarias e instrumentadas.
- `app/schemas`: esquemas Room.
- `.github/workflows`: compilación/CI y publicación.

## Versiones, CI y firma

La fuente de versión Android es `versionName`/`versionCode` en `app/build.gradle.kts`. GitHub Actions compila y ejecuta pruebas unitarias en push a `main` y pull requests hacia `main`; también compila el APK debug y el paquete AndroidTest. Una etiqueta `v*` activa la publicación si coincide exactamente con `versionName`.

La compilación release actual se firma con la clave debug de Android porque no hay clave de producción configurada. El flujo adjunta el APK con nombre `Axiom-vX.Y.Z-debug-signed.apk`, `SHA256SUMS.txt` y `SIGNING.txt` a una GitHub Release. Es un artefacto de desarrollo: no equivale a firma de producción y solo puede actualizar instalaciones firmadas con la misma clave debug. No se incluyen claves de firma en el repositorio. Las etiquetas publicadas deben ser inmutables; nunca reutilices una etiqueta.

## Licencias

El código propio se distribuye bajo GNU AGPL versión 3 o, a elección, cualquier versión posterior; consulta [LICENSE](LICENSE). El código de libmobi conserva LGPL-3.0-or-later y sus avisos/textos incluidos en `app/src/main/cpp/third_party/libmobi/`. El conversor y su aviso conservan la licencia indicada en su fuente; miniz incluye aviso de dominio público. Las dependencias Gradle de terceros mantienen sus propias licencias, que no son reemplazadas por la licencia del proyecto.