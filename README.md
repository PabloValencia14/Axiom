# Axiom

Axiom es una aplicación Android de código abierto para leer y anotar documentos. Incluye biblioteca y progreso locales; lector de EPUB, TXT/Markdown, FB2, DOCX y PDF; cómics CBZ; conversión local de MOBI/AZW/AZW3 sin DRM a EPUB; búsqueda, marcadores, notas y escritura con stylus. En PDF incluye edición no destructiva de páginas, OCR/copia buscable y exportación de páginas a imagen o texto. Las firmas visuales de PDF son anotaciones, no firmas criptográficas.

## OCR y privacidad

El OCR y la generación de capas de texto PDF usan Google ML Kit Text Recognition con modelo latino incluido. No se anuncia reconocimiento de alfabetos no latinos. El escáner de documentos usa ML Kit Document Scanner y puede obtener componentes mediante Google Play Services. El OCR procesa localmente el documento seleccionado; Axiom no envía automáticamente documentos a un servidor propio.

La biblioteca, el progreso y las anotaciones se guardan en el dispositivo. Integraciones opcionales como Google Drive, catálogos en línea y servicios configurados por el usuario (por ejemplo OpenRouter) transmiten datos a esos proveedores cuando se activan; revisa sus políticas. No publiques libros, claves, bases de datos, capturas privadas ni datos personales en incidencias o pull requests.

La traducción completa usa por defecto modelos locales de ML Kit (Google Translate): necesita descargar los idiomas inicialmente y después procesa el texto en el dispositivo, sin solicitudes remotas por párrafo. Si se desactiva la opción local, el texto se envía a Google Translate por Internet. No hace OCR ni traduce imágenes. La importación y los adjuntos de Drive bloquean rutas o datos privados de la aplicación. La autorización de Drive está ligada a la cuenta; los tokens heredados sin cuenta asociada requieren volver a autorizar.

## Funciones y límites actuales

- La traducción de documentos selecciona por defecto ML Kit, proporcionado por Google Translate: elige el idioma de origen y descarga los modelos por Wi-Fi la primera vez. Después, el texto se procesa en el dispositivo, sin cuota de solicitudes por párrafo. La traducción automática puede contener errores, también en frases generales; requiere revisión y no garantiza fidelidad científica. La página actual y el modo en línea siguen usando Google Translate por Internet. Consulta la [documentación de ML Kit](https://developers.google.com/ml-kit/language/translation), [Cloud Translation](https://cloud.google.com/translate) y su [aviso sobre traducciones automáticas](https://docs.cloud.google.com/translate/attribution#disclaimer).

- OCR orientado a texto latino; el reconocimiento puede no encontrar texto o cometer errores según calidad, resolución, idioma y diseño de página.
- MOBI/AZW/AZW3 requieren documentos sin DRM y conversión válida; no se elimina ni evade DRM.
- CBZ está implementado; CBR, DOC, RTF, DJV/DJVU y CHM no tienen motor de lectura en esta versión, aunque aparezcan como formatos reconocidos por la aplicación.
- La firma PDF solo dibuja una marca visual en una copia; no incorpora certificado, identidad verificada ni validación criptográfica.
- La traducción de PDF reconstruye párrafos nativos fragmentados entre operadores y sustituye la prosa dentro de su zona original. Mantiene el número de páginas, sus cajas y rotaciones, las imágenes y los gráficos vectoriales en sus posiciones; no cubre el texto con rectángulos ni convierte las páginas a imágenes. Fórmulas y citas bibliográficas (autor-año) permanecen locales y conservan sus glifos y coordenadas originales. La prosa traducida usa el estilo base de cada tramo, con fuente compatible, colores y modo de relleno/trazo; los estilos mixtos dentro de un tramo pueden uniformarse. El archivo original permanece intacto.
- La sincronización y servicios remotos dependen de credenciales, red y disponibilidad de terceros; la copia local sigue siendo independiente.
- El escaneo automático agrupa el inventario, conserva metadatos existentes, anotaciones y documentos en la papelera, y se limita a uno cada 30 segundos al reanudar la app; la actualización explícita sigue disponible de inmediato.
- La búsqueda, los filtros y el ordenamiento de la biblioteca se calculan fuera del hilo principal; solo la coincidencia de texto usa un debounce de 250 ms.
- El lector comparte una caché de imágenes de 32–128 MiB con una ventana móvil de páginas; el bloqueo de movimiento impide desplazar o ampliar con gestos sin desactivar la navegación por páginas.
- La biblioteca adapta sus cuadrículas a la pantalla. El lector ofrece opciones de tema accesibles de 48 dp, acceso funcional a Ajustes generales y controles de página fija para CBZ (sin controles de reflujo EPUB). La lectura en voz alta informa si no hay texto y Detener cancela la preparación pendiente.
- La traducción nativa admite PDF, DOCX, EPUB, FB2 y TXT; procesa texto seleccionable, sin OCR, y deja intacto el original. En PDF conserva el anclaje del bloque y puede reducir la fuente hasta un 35 % en párrafos de varias líneas, o un 55 % en etiquetas de una línea; el interlineado puede reducirse un 10 %. Las etiquetas cortas pueden usar hasta un em de espacio contiguo cuando no invade otros elementos. El texto pequeño, especialmente en tablas y figuras, puede requerir ampliar la página. Si no cabe el texto completo o se solapa, rechaza la exportación con la ubicación afectada: no recorta texto, desplaza recursos ni añade páginas. DOCX conserva estructura y recursos, aunque puede repaginarse; EPUB mantiene el paquete y sus recursos, pero se redistribuye. MOBI/AZW/AZW3 requieren conversión explícita a EPUB y también se redistribuyen.
- La traducción completa procesa todas las páginas con texto nativo compatible, conserva las páginas sin texto, informa del tratamiento local o del envío a Google Translate y admite cancelación; los errores no producen resultados incompletos y cada salida usa un nombre único. Un HTTP 429 del modo en línea cancela la exportación sin publicar una copia parcial. La fidelidad de PDF tiene límites: no equivale a OCR; fuentes o procedimientos de glifos Type3, texto de escritura vertical, transparencias y efectos complejos pueden provocar un rechazo explícito.
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

Verificación de la sustitución de texto PDF (2026-10-16, Windows/JDK 17): 166 pruebas JVM en 31 clases superadas, sin fallos, errores ni omisiones; APK debug y AndroidTest compilados. En la Xiaomi Pad 7 autorizada se completó un paper de 12 páginas mediante ML Kit local: 646 respuestas, 1.745 glifos protegidos conservados en sus coordenadas y todas las páginas abiertas y renderizadas en el motor real. La prueba comprobó la conservación del original, del número de páginas, sus dimensiones y los límites de los gráficos. Se corrigieron los números y cabeceras de tablas, el espaciado inicial de operadores TJ y los solapamientos entre bloques pendientes. La calidad lingüística automática requiere revisión; esta comprobación no garantiza precisión científica ni compatibilidad con cualquier PDF. La verificación del APK release está pendiente.

Límite observado en un segundo paper: la prueba de G3PARM (24 páginas, DOI `10.1007/s10115-011-0419-z`) se rechazó en la página 6, operador 9372, por colisión del texto traducido junto a una fórmula. Conservó el original y no publicó una copia parcial. Ese documento no está validado como compatible.

La prueba instrumentada `PdfReadingTest#realPaperWithSelectedProvider` solo se activa al indicar `-e translationPdfPath RUTA_PDF` junto a `-e class org.readera.openreadera.PdfReadingTest#realPaperWithSelectedProvider` en el comando `am instrument` del dispositivo autorizado. Usa ML Kit real de inglés a español por defecto; `-e translationEngine google` selecciona el proveedor en línea y requiere autorización para enviar el texto. Escribe la salida y los renderizados en una carpeta de evidencias propia y comprueba que el original no cambia.

El código JNI/C++ y la conversión MOBI se compilan desde `app/src/main/cpp`; todas las fuentes necesarias, incluyendo la selección vendorizada de libmobi, están en el repositorio. No depende de archivos Gradle ni bibliotecas en el directorio padre.

## Estructura

- `app/src/main/java`: app Android, lector, motores, persistencia e integraciones.
- `app/src/main/cpp`: JNI, conversor y código vendorizado necesario.
- `app/src/test` y `app/src/androidTest`: pruebas unitarias e instrumentadas.
- `app/schemas`: esquemas Room.
- `.github/workflows`: compilación/CI y publicación.

## Versiones, CI y firma

La fuente de versión Android es `versionName`/`versionCode` en `app/build.gradle.kts`. GitHub Actions compila y ejecuta pruebas unitarias en push a `main` y pull requests hacia `main`; también compila el APK debug y el paquete AndroidTest. Una etiqueta `v*` activa la publicación si coincide exactamente con `versionName`.

La compilación release actual se firma con la clave debug de Android porque no hay clave de producción configurada. El flujo crea un borrador con `Axiom-vX.Y.Z-debug-signed.apk`, `SHA256SUMS.txt` y `SIGNING.txt`. Antes de publicarlo hay que probar el APK y comprobar la continuidad de firma: la clave debug efímera del ejecutor de CI no coincide necesariamente con las instalaciones existentes. Si se sustituye por el APK probado y firmado localmente, deben actualizarse también sus sumas y aviso de firma. Es una firma de desarrollo y solo permite actualizar instalaciones con la misma clave. No se incluyen claves en el repositorio. Las etiquetas publicadas deben ser inmutables.

## Licencias

El código propio se distribuye bajo GNU AGPL versión 3 o, a elección, cualquier versión posterior; consulta [LICENSE](LICENSE). El código de libmobi conserva LGPL-3.0-or-later y sus avisos/textos incluidos en `app/src/main/cpp/third_party/libmobi/`. El conversor y su aviso conservan la licencia indicada en su fuente; miniz incluye aviso de dominio público. Las dependencias Gradle de terceros mantienen sus propias licencias. La insignia `google_translate_attribution.png` procede del [paquete oficial de atribución](https://docs.cloud.google.com/translate/attribution), se conserva sin modificar y está sujeta a sus directrices de marca.
