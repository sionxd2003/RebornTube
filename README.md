# RebornTube

Cliente Android de YouTube sin anuncios propios, restaurado a partir del proyecto original de RebornTube y actualizado para usar NewPipe Extractor. El tema predeterminado es Gruvbox oscuro; también se puede elegir Gruvbox claro, claro, negro puro o seguir el sistema.

## Apariencia y funciones
- Tema claro, oscuro, negro puro, igual al sistema o Gruvbox claro/oscuro, con modo ambiente limitado al reproductor.
- Inicio con feed compacto, tendencias por categoría, directos, suscripciones y novedades.
- Modo de ahorro de datos: oculta imágenes en listas y desactiva la precarga de streams.
- Búsqueda separada de canales y videos, con sugerencias e historial; las sugerencias se pueden desactivar.
- Reproductor con controles propios, calidad, subtítulos, capítulos navegables, velocidad recordada, salto por doble toque configurable, orientación de pantalla completa, compartir desde el momento actual, temporizador de sueño, recordatorio configurable para tomar descansos, reproducción automática configurable al abrir o de recomendaciones y cuenta regresiva opcional, pausa opcional al apagar la pantalla, contenido relacionado configurable, modo música con anterior/siguiente, aleatorio y repetir cola, y reproductor sin conexión con cola, aleatorio, repetición y color de acento adaptable. El audio descargado conserva su formato y extensión originales, incluye la carátula local y puede seguir sonando al minimizar el reproductor; YouTube no ofrece FLAC real.
- SponsorBlock con selección de categorías, segmentos visibles, votos y contribuciones manuales confirmadas por la persona; DeArrow opcional para títulos y miniaturas aprobados, y envío manual de propuestas.
- Integración Piped opcional para autenticarse en una instancia y mostrar su feed en Novedades; las búsquedas y reproducción continúan usando NewPipe Extractor.
- Biblioteca con historial, Me gusta, No me gusta, Ver más tarde, cola, listas y descargas.
- Importación y exportación de biblioteca, y compatibilidad con Google Takeout.
- Avisos configurables de nuevos videos de canales suscritos (frecuencia y uso solo de Wi-Fi).
- Emisión a dispositivos compatibles, ventana flotante automática para video y reproducción de audio en segundo plano con controles del sistema.
- Región de contenido seleccionable desde el icono de mundo, con tendencias y búsquedas adaptadas al país elegido.
- Feed local que incorpora títulos y canales del historial reciente para generar recomendaciones locales, sin enviar el historial a YouTube.
- Avisos locales de nuevos vídeos para suscripciones activadas con la campana de canal; pueden comprobarse periódicamente mediante WorkManager y requieren permiso de notificaciones de Android.
- Donaciones voluntarias a través de la página externa de Ko-fi; la app no procesa pagos ni ofrece funciones a cambio.

## Integración con la app compañera para Fire TV
Abre **Transmitir a**, introduce los 6 dígitos temporales que muestra la app receptora y toca **Enviar a RebornTube para Fire TV**. Si el descubrimiento automático del Fire TV no funciona, escribe la dirección IP mostrada en la pantalla de TV y usa **Conectar por IP**. Ambas apps deben poder comunicarse por la red local. El código se usa para el primer emparejamiento; después, la app móvil conserva en su almacenamiento privado un token de sesión por Fire TV y permite enviar videos sucesivos sin volver a introducir la IP ni el código. Puedes borrar esos vínculos desde Ajustes.

La app Fire TV debe anunciar por DIAL el app id `RebornTube` y aceptar un `POST` con `Content-Type: application/x-www-form-urlencoded` en `Application-URL/RebornTube`. El primer POST usa `code`; los siguientes usan `sessionToken`. Al emparejar, el receptor devuelve HTTP 200 con JSON `{"sessionToken":"..."}`. Los campos opcionales del video son `videoId`, `videoUrl`, `title` y `live`; este último indica si es un directo. El código y el token se transmiten por HTTP dentro de la red local: úsalos solo en una red confiable; no se requiere redirección de puertos desde Internet.

Si Fire TV aparece en un hotspot pero no en el router doméstico, conecta ambos dispositivos a la misma red principal (no a una red de invitados o VLAN separada), desactiva el aislamiento de clientes/AP y permite SSDP multicast `239.255.255.250:1900/UDP` y conexiones locales `TCP 8765`. No abras ni reenvíes esos puertos a Internet.

## Desarrollo

- Android Studio con JDK 17 y Android SDK Platform 35.
- Abrir `RebornTube` en Android Studio, sincronizar Gradle y ejecutar la configuración `app`.
- En Windows: `.\gradlew.bat assembleDebug lintDebug testDebugUnitTest`
- En macOS/Linux: `./gradlew assembleDebug lintDebug testDebugUnitTest`
- GitHub Actions ejecuta build, lint y pruebas unitarias en cada push y pull request.

La versión visible de cada compilación usa CalVer (`yyyy.MM.dd`) y el código interno usa `yyyyMMdd`.

La app usa NewPipe Extractor, distribuido bajo GPLv3. Si distribuyes RebornTube, revisa y cumple los requisitos de esa licencia.

SponsorBlock y DeArrow usan datos/API comunitarios sujetos a CC BY-NC-SA 4.0. Atribución: [SponsorBlock](https://sponsor.ajay.app/) y [DeArrow](https://dearrow.ajay.app/). No se automatizan envíos: las propuestas y votos solo se transmiten tras una acción explícita de la persona. Las contribuciones requieren revisar y cumplir la licencia y sus condiciones antes de distribuir la app.

Antes de publicar el repositorio, el propietario debe elegir y añadir una licencia para el código de RebornTube. La licencia GPLv3 de NewPipe Extractor y las condiciones de las fuentes de datos de terceros siguen aplicando a la distribución de la app.
