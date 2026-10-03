# Axiom

Axiom es una aplicación Android de código abierto para leer y anotar documentos. Incluye biblioteca y progreso locales; lector de EPUB, TXT/Markdown, FB2, DOCX y PDF; cómics CBZ; conversión local de MOBI/AZW/AZW3 sin DRM a EPUB; búsqueda, marcadores, notas y escritura con stylus. En PDF incluye edición no destructiva de páginas, OCR/copia buscable y exportación de páginas a imagen o texto. Las firmas visuales de PDF son anotaciones, no firmas criptográficas.

## OCR y privacidad

El OCR y la generación de capas de texto PDF usan Google ML Kit Text Recognition con modelo latino incluido. No se anuncia reconocimiento de alfabetos no latinos. El escáner de documentos usa ML Kit Document Scanner y puede obtener componentes mediante Google Play Services. El OCR procesa localmente el documento seleccionado; Axiom no envía automáticamente documentos a un servidor propio.

La biblioteca, el progreso y las anotaciones se guardan en el dispositivo. Integraciones opcionales como Google Drive, catálogos en línea y servicios configurados por el usuario (por ejemplo OpenRouter) transmiten datos a esos proveedores cuando se activan; revisa sus políticas. No publiques libros, claves, bases de datos, capturas privadas ni datos personales en incidencias o pull requests.

## Funciones y límites actuales

- OCR orientado a texto latino; el reconocimiento puede no encontrar texto o cometer errores según calidad, resolución, idioma y diseño de página.
- MOBI/AZW/AZW3 requieren documentos sin DRM y conversión válida; no se elimina ni evade DRM.
- CBZ está implementado; CBR, DOC, RTF, DJV/DJVU y CHM no tienen motor de lectura en esta versión, aunque aparezcan como formatos reconocidos por la aplicación.
- La firma PDF solo dibuja una marca visual en una copia; no incorpora certificado, identidad verificada ni validación criptográfica.
- La sincronización y servicios remotos dependen de credenciales, red y disponibilidad de terceros; la copia local sigue siendo independiente.

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

En Windows usa `gradlew.bat`. `assembleDebugAndroidTest` crea el paquete instrumentado, incluido el fixture MOBI de prueba generado desde Gradle; para ejecutar pruebas instrumentadas hace falta un emulador o dispositivo conectado:

```bash
./gradlew connectedDebugAndroidTest
./gradlew installDebug
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