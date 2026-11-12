# Procedencia y licencias

## Base de esta aplicación

Se ha adaptado el código de [jqssun/android-airplay-server](https://github.com/jqssun/android-airplay-server),
revisión `a46cca0a6a1274e9e33c0e750f0f1c1eb1d881ea`, bajo GNU GPL versión 3.
Se conserva el texto de la licencia en [`LICENSE`](LICENSE) y los avisos presentes en los archivos originales.
Los cambios de este proyecto se distribuyen también bajo GPLv3.

Cambios principales: identidad AirPlay PiP, pantalla de conexión Android TV, paleta visual,
ventana flotante de mirroring, selección de presentación, controles con mando y preparación
idempotente de dependencias nativas. El paquete Kotlin/JNI original se conserva por compatibilidad;
el identificador de instalación es distinto: `io.github.airplaypip`.

## Dependencias nativas

Las revisiones exactas y URLs están en [`native-dependencies.json`](native-dependencies.json).
`scripts/bootstrap_native.py` descarga sus fuentes, incluidos sus archivos de licencia, a
`app/src/main/cpp/third_party/`. UxPlay implementa AirPlay/RAOP; FFmpeg aporta el decodificador
ALAC; libplist procesa property lists; openssl-cmake construye OpenSSL.
OpenSSL 3.4.4 se descarga durante la compilación nativa, según `app/src/main/cpp/CMakeLists.txt`.
Las dependencias Android, incluido Oboe, están declaradas en `gradle/libs.versions.toml` y `app/build.gradle.kts`.

Antes de distribuir un APK, incluir los avisos exigidos por las dependencias y ofrecer el código
fuente correspondiente, incluidos los parches y scripts de compilación, conforme a sus licencias.
No publicar únicamente el APK como si fuera software propietario.

## Referencia de ventanas flotantes

[mhoogenbosch/ha-pipup](https://github.com/mhoogenbosch/ha-pipup) y su app
[mhoogenbosch/PiPup](https://github.com/mhoogenbosch/PiPup) sirvieron como referencia conceptual.
No se ha incorporado código de esos proyectos ni se requiere Home Assistant.

AirPlay es una marca de Apple Inc. Este proyecto no está afiliado a Apple.
