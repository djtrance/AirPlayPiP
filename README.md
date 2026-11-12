# AirPlay PiP para Android TV

Receptor AirPlay para duplicar la pantalla de un iPhone, iPad o Mac en Android TV,
con pantalla completa, PiP del sistema y ventana flotante sobre otras aplicaciones.
Basado en el motor real de [android-airplay-server](https://github.com/jqssun/android-airplay-server),
no en una simulación ni en un reproductor de vídeos locales.

## Qué incluye

- Recepción AirPlay/RAOP mediante UxPlay y JNI; mirroring H.264/HEVC y audio del upstream.
- Inicio automático del receptor al abrir la app y descubrimiento mDNS.
- Pantalla de conexión para TV con nombre del receptor, direcciones IPv4 e instrucciones.
- Pantalla completa automática al comenzar el mirroring; **OK/D-pad** muestra los controles,
  **Atrás** vuelve a la pantalla de conexión sin cortar la sesión.
- Botón **PiP** con elección explícita entre PiP nativo, si existe, y ventana flotante.
- Ventana flotante con superficie independiente (predeterminada para HDMI) o TextureView,
  recuperación de la superficie de salida y controles de posición con el mando, sin abrir otra sesión AirPlay.
- Interfaz oscura de inspiración Apple y recursos de interfaz en español e inglés.
- Ajustes avanzados, PIN opcional, registros y soporte de reproducción AirPlay heredados.
- Modo experimental de **dos receptores AirPlay**: una segunda identidad, layout lado a lado o PiP,
  selección de pantalla activa para audio y preferencia experimental de salida Bluetooth por pista.
- En **PiP**, el modo de decodificador Automático cambia a software H.264 y vuelve al hardware al salir;
  requiere un IDR/IRAP nuevo y no cubre contenido HEVC sin decodificador compatible.
- Desde **0.1.11**: servidor web temporal para subir APKs desde la LAN con código de 4 dígitos o modo sin código,
  validación de paquete/versión/certificado y confirmación de instalación del sistema.
- Desde 0.1.3: botones **Controles PiP** y **Cerrar aplicación** en la propia app, panel flotante
  independiente del vídeo, diagnóstico por etapas y modo experimental de salida por lienzo CPU.

**Estado:** APK debug **0.1.11** compilado y firmado, con bibliotecas ARM64, ARMv7 y x86_64.
En la **TCL C715, Android TV 11**, el usuario confirma que el decodificador software funciona,
pero reporta unos 5 FPS. Con hardware, la foto identifica **`OMX.realtek.video.decoder`**, RX activo,
DEC/OUT=0 y una `IllegalStateException` sin mensaje. Eso no prueba por sí solo si falla la
configuración, una operación de buffers, la referencia del stream o la disponibilidad de la VPU.
**0.1.5 añade configuración conservadora para Realtek, un perfil hardware estricto y diagnóstico
del punto exacto del error. Esa ruta hardware todavía no está validada físicamente en la TCL.**
La compilación y las pruebas de código no certifican estabilidad ni compatibilidad del compositor del fabricante.

Desde **0.1.11**, Ajustes → **Actualizar app por red local** inicia opcionalmente un servidor temporal
para transferir un APK desde otro equipo de la misma LAN. Se puede elegir un código de 4 dígitos
(se bloquea tras 5 intentos incorrectos) o el explícito **modo sin código**. Solo acepta direcciones
privadas, limita el APK a 200 MiB y verifica nombre de paquete, versión superior y certificado de firma
antes de entregar el archivo al instalador oficial de Android.
La instalación nunca es silenciosa y siempre requiere confirmación en la TV.

### Dos pantallas AirPlay

1. En Ajustes activa **Segundo receptor AirPlay**.
2. Desde cada dispositivo Apple, inicia duplicación y selecciona **AirPlay PiP** y **AirPlay PiP 2**.
3. En **Diseño de dos pantallas** elige **Lado a lado** o **Imagen en imagen**. Al seleccionar la
   etiqueta Principal/Segundo en la imagen se cambia el audio cuando **Audio activo = Pantalla seleccionada**.
   También se puede fijar Primaria, Secundaria o Silenciar ambas.

La segunda identidad usa otra instancia RAOP, puerto e identidad, decodificador y superficie; no es
un selector que sustituya a la primera. El TV debe poder decodificar y componer los dos streams.
Empieza por H.264, baja FPS/resolución si es necesario y comprueba RX/DEC/OUT de ambos. En la C715
hay un problema hardware/HDMI conocido con una sesión: **dual simultáneo es experimental y no se ha
validado en esa TCL**. «Permitir nuevas conexiones» controla reemplazos dentro de cada identidad.

### Bluetooth para el audio AirPlay

Conecta primero auriculares/parlante Bluetooth desde los ajustes Android de la TV y, en
**Ajustes → Audio output → AirPlay audio device**, elige el dispositivo Bluetooth. Oboe solicita
esa salida solo para la pista de audio de AirPlay; el volumen AirPlay se aplica como ganancia de la
pista para no cambiar a propósito el volumen global de HDMI. El sistema/firmware puede ignorar el
enrutamiento preferido o dirigir toda la TV a Bluetooth; no se puede garantizar que HDMI siga
sonando por los altavoces internos. Usa **System default (TV/HDMI)** para revertir. Verificarlo en
la C715, incluyendo desconectar los auriculares.

## Compilar

Requisitos: Git, Python 3, **JDK 21**, Android SDK **37**, Build Tools **37.0.0**,
NDK **27.0.12077973**, CMake **3.22.1**, y las herramientas nativas del host
(`make`, Perl y ensamblador del host cuando lo requiera FFmpeg/OpenSSL).
En macOS instalar las Command Line Tools de Xcode. Se necesita internet para Gradle y las fuentes nativas.

1. Instalar el toolchain con Android Studio/SDK Manager y configurar `JAVA_HOME`.
2. Indicar la ruta del SDK con `ANDROID_HOME` o un archivo local no versionado `local.properties`:

   ```properties
   sdk.dir=/ruta/absoluta/al/Android/sdk
   ```

3. Desde este directorio:

   ```sh
   python3 scripts/bootstrap_native.py
   ./gradlew :app:assembleDebug
   ```

El APK estará en `app/build/outputs/apk/debug/app-debug.apk`.
Se generan ARM64, ARMv7 y x86_64; la primera compilación nativa puede tardar varios minutos.
No se necesita un almacén de firma para debug. La distribución release requiere firma propia.

Este directorio no necesita ser un repositorio Git para preparar las dependencias.
No usar `git submodule update` aquí: las revisiones se fijan en `native-dependencies.json`.
Gradle aplica automáticamente los parches de UxPlay como un único cambio idempotente;
si entran en conflicto con cambios locales, falla sin restablecer archivos.

### Entorno instalado en este Mac

Se instalaron Temurin **21.0.13+9** en
`~/Library/Java/JavaVirtualMachines/jdk-21.0.13+9/Contents/Home` y los componentes Android en
`~/Library/Android/sdk`. `local.properties` apunta a ese SDK y no se versiona.
macOS detecta el JDK mediante `java_home`; no se modificaron los archivos de configuración de la shell.
Para disponer de ADB y SDK Manager en una terminal:

```sh
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
```

Para repetir la compilación y las verificaciones desde este directorio:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --console=plain
```

El APK de salida para actualizar otras instalaciones está en
`app/build/outputs/apk/debug/app-debug.apk`. Como la versión subida debe ser mayor que la instalada,
incrementar `versionCode`/`versionName` antes de compilar. El certificado debug debe ser el mismo
que el de la app instalada (en desarrollo, usa el mismo `~/.android/debug.keystore`; una firma debug
de CI puede ser diferente y el actualizador la rechazará).

También se incluye `.github/workflows/android.yml` para generar un APK debug al subir el
proyecto a GitHub; no publica releases ni usa secretos de firma.

## Instalar y conectar

```sh
adb connect IP_DE_LA_TV:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n io.github.airplaypip/io.github.jqssun.airplay.MainActivity
```

El puerto y el emparejamiento ADB dependen de la TV; los equipos con depuración inalámbrica
moderna pueden requerir primero `adb pair` y un puerto distinto.

### Actualizar por red local

1. Compilar una versión posterior firmada con **el mismo certificado** que la app instalada.
2. En la TV: **Ajustes → Actualizar app por red local → Iniciar servidor de carga**.
3. Si Android lo solicita, habilitar «Permitir desde esta fuente» para AirPlay PiP.
4. En un teléfono u ordenador conectado a la misma LAN privada, abrir una de las direcciones
   mostradas en la TV, escribir el código de 4 dígitos (o dejarlo vacío si se eligió sin código),
   seleccionar el APK y pulsar validar/enviar.
5. Esperar la validación en la TV y pulsar **Instalar actualización**; confirmar en el diálogo del sistema.
   Detener el servidor al terminar.

El listener usa un puerto aleatorio y solo corre mientras se solicita explícitamente; acepta una
sola carga y se detiene después de validarla. No hay TLS. El código corto es de un solo uso y bloquea
la sesión tras 5 errores; el modo sin código permite a cualquier cliente de la LAN privada intentar
subir un APK. Usar ambos modos solo en redes confiables, preferiblemente el código. No se puede instalar
APK de otro paquete, versión igual/anterior o
certificado distinto. Android conserva el último control de instalación. El servidor de carga sirve
el archivo, **no** publica ni genera APKs; la compilación/firma siguen siendo manuales.

**Error al analizar el paquete en 0.1.1:** el `taskAffinity` del acceso de controles contenía
un guion no permitido por el analizador de Android. Usar **0.1.2 o posterior**; no hace falta
desinstalar 0.1.0 ni borrar sus ajustes. Para este diagnóstico no basta con que la firma y aapt
pasen: se añadió validación del manifiesto antes de cada build y una prueba de regresión.

1. Abrir **AirPlay PiP** desde el inicio de Android TV y conceder los permisos solicitados.
2. Conectar TV y dispositivo Apple a la **misma subred**, sin aislamiento de clientes Wi-Fi.
3. En el dispositivo Apple: **Centro de control → Duplicar pantalla → AirPlay PiP**.
   El nombre se puede cambiar en Ajustes.
4. Pulsar OK en pantalla completa para acceder al botón PiP.

### Dos modos pequeños, no equivalentes

| Modo | Requisitos | Regresar a pantalla completa |
| --- | --- | --- |
| PiP del sistema | TV con la función PiP habilitada por el fabricante | Controles de la ventana de Android |
| Ventana flotante | Permiso «Mostrar sobre otras apps»; sesión de mirroring activa | Abrir AirPlay PiP desde el inicio o usar la acción «Pantalla completa» de la notificación |

La ventana flotante **no captura el mando por defecto**, para poder seguir usando HDMI o la app
que queda debajo. No es un PiP nativo y se utiliza solamente para mirroring, no para sesiones
HLS/solo audio. Se retira al desconectar el emisor o detener el receptor.

#### Seleccionar y mover el PiP mientras ves HDMI

Desde **0.1.3**, no es necesario encontrar otra app en el launcher:

1. Durante la duplicación, pulsa OK y abre **Controles PiP** en la propia app (también está en la
   barra inferior al salir de pantalla completa).
2. Elige **Abrir PiP con controles** o **Controles en 10 segundos**. La segunda opción da tiempo
   para cambiar a HDMI antes de que el panel solicite el mando.
3. El panel es otra ventana dibujada por software, independiente de la superficie de vídeo.
   Tiene botones **Siguiente esquina**, **Mover con flechas**, **Recrear salida de vídeo**,
   **Ocultar menú PiP**, **Pantalla completa** y **Cerrar aplicación**. Al ocultarlo queda un botón
   compacto enfocado; pulsa OK para volver a abrir las acciones. Atrás oculta el menú manteniendo
   ese acceso; «Devolver mando a la TV» libera el foco, e Inicio Android lo devuelve al launcher.
4. Con **Mover con flechas**, las flechas mueven el vídeo y OK/Atrás libera el mando.

Siguen disponibles los accesos de la notificación, launcher y Ajustes rápidos:

1. Abrir la notificación de AirPlay PiP y elegir **Controlar PiP**. Tocar la notificación también
   selecciona la ventana, sin abrir `MainActivity` ni pasar a pantalla completa.
2. Cerrar el panel de notificaciones si la TV lo mantiene abierto. La ventana seleccionada muestra
   un borde azul e instrucciones; **↑ ↓ ← →** la mueven entre las cuatro esquinas.
3. **OK** o **Atrás** terminan el control y devuelven el mando a HDMI. También se libera
   automáticamente tras **15 segundos** sin usarlo.
4. **Menú**, si la TV entrega esa tecla a la app, abre pantalla completa. La notificación ofrece
   una acción de pantalla completa independiente.

Hay dos accesos alternativos: **Controles PiP** en el launcher (actividad sin pantalla propia,
separada de la app principal), y un mosaico del mismo nombre que puedes añadir a **Ajustes rápidos**
si la TV permite editarlos. La disponibilidad de estos paneles y la conservación de HDMI al abrir
el launcher dependen del fabricante. No se interceptan teclas globalmente ni se pide accesibilidad.
Si la TV no entrega las flechas al overlay, la acción **Siguiente esquina** de la notificación
mueve la ventana sin capturar el mando. La posición se guarda también en Ajustes.

#### PiP de YouTube u otras apps mientras HDMI reproduce

Android TV solo puede poner en PiP otra app si **esa app y el firmware** ofrecen el PiP del sistema.
AirPlay PiP no puede capturar ni reubicar la superficie de YouTube dentro de su propia ventana, porque
Android aísla las superficies entre apps. En la TCL, comprueba **Ajustes de Android → Apps → Acceso
especial → Imagen en imagen → YouTube** si aparece; prueba un vídeo y pulsa **Inicio**. Si la app vuelve
al launcher sin una ventanita, la app/firmware no habilita PiP para esa combinación y no hay un modo
universal de forzarlo desde este receptor.

Referencias: [PiP de Android Developers](https://developer.android.com/develop/ui/views/picture-in-picture)
y [ayuda de YouTube sobre PiP](https://support.google.com/youtube/answer/7552722?hl=es). La ayuda de YouTube
describe principalmente móviles; la disponibilidad en Android TV depende de la app y del fabricante.

#### Prueba hardware conservadora para Realtek (0.1.5)

La configuración anterior probaba claves de baja latencia aunque el códec no anunciara esa
función. Desde 0.1.5 los componentes `OMX.realtek.*` y `c2.realtek.*` usan una configuración
MediaCodec básica incluso en Automático: sin claves vendor/low-latency, límites adaptativos,
color o descarte de frames forzados. Es una corrección de compatibilidad para probar, no evidencia
de que esos ajustes fueran la causa ni de que el firmware permita compartir la VPU con HDMI.

Para una prueba sin fallback encubierto:

1. **Controles PiP → Hardware H.264 conservador · 720p/24 · reconectar**.
2. Confirmar: guarda hardware estricto, H.264, 1280×720/24 FPS, salida por superficie independiente
   y reinicia el receptor si está activo. La sesión y el audio se interrumpen.
3. Detener y reconectar la duplicación desde Apple; después usar **Controles en 10 segundos** y activar HDMI.
4. Verificar `decoder=hardware_compat`. Si no hay hardware compatible, informa sin cambiar ajustes;
   si falla durante la reproducción, no cambia silenciosamente a software.

El diagnóstico distingue `started` de `decoding` y `started_no_output`/`stalled_after_output`.
`gen` identifica cada instancia nueva; `inGen` sus entradas aceptadas; `IDRg` cuenta imágenes de
referencia IDR/IRAP aceptadas en esa instancia. SPS/VPS sirven para configurar pero **no** cuentan
como referencia. `ERR` es acumulado en la sesión: ahora se indica antigüedad y operación del último
error (`configure`, `queue_input`, `dequeue_output`, `release_output`, etc.), incluso con mensaje vacío.
El detalle completo y la pila se envían a **Diagnóstico → Exportar** y a logcat.

Si vuelve a fallar, exportar ese registro antes de cerrar o reiniciar. No interpretar una excepción
genérica como `ERROR_RECLAIMED`: solo se informa del código/flags recuperable/transitorio si Android
los proporciona. Una app normal no puede reservar el hardware contra una revocación del sistema.

#### RX activo, DEC 0: probar decodificador software

**Decodificador de vídeo** y **salida de vídeo flotante** son ajustes distintos. El modo `software`
de salida de 0.1.3 solo copiaba/dibujaba imágenes ya decodificadas: no evitaba el decodificador de
hardware que podía detenerse al activar HDMI, ni podía producir imagen si se esperaba otro IDR.

Para probar otra ruta en la TCL C715:

1. Abrir **Controles PiP → Perfil H.264 software · 720p/24 · reconectar**.
2. Leer y confirmar: selecciona un decodificador de vídeo por software, desactiva HEVC, anuncia
   **1280×720 a 24 FPS** y elige salida por lienzo CPU. Guarda esos ajustes y reinicia el receptor si
   está activo, interrumpiendo la sesión y el audio. Si no encuentra un decodificador compatible,
   informa del problema sin cambiar ajustes.
3. En el Mac/iPhone/iPad, **detener y conectar de nuevo la duplicación**. No basta con recrear el
   overlay: el nuevo decodificador necesita los parámetros del stream y un fotograma de referencia nuevo.
4. Abrir **Controles en 10 segundos** y cambiar a HDMI. No es necesario volver a aplicar el perfil.
5. Comprobar `decoder=software` y el nombre del componente (habitualmente `c2.android.avc.decoder`
   u `OMX.google.h264.decoder`), además de RX/DEC/OUT. `IN` cuenta entradas aceptadas por el códec,
   `ERR` sus excepciones, y `state=waiting_idr` indica que falta un fotograma de referencia.

La selección estricta no vuelve silenciosamente al decodificador de hardware ni aplica claves
de baja latencia específicas de fabricantes a los componentes software. Si falla, muestra el error.
El perfil consume CPU y no garantiza que la TCL lo soporte o tenga rendimiento suficiente.
No se cambia un decodificador en medio de un GOP ni se reproduce a ciegas un fotograma antiguo.

Si funciona pero resulta lento, se puede mantener el decodificador **Software**, elegir salida
**Superficie independiente** para evitar la copia Canvas de hasta 10 FPS y reducir la resolución
anunciada (por ejemplo, `640x360`) en Ajustes. Se pierde detalle y requiere reconectar; la mejora
de FPS depende del dispositivo y todavía debe medirse, no está garantizada.

Para deshacerlo, **Ajustes → Decodificación → Decodificador de vídeo de duplicación → Automático**.
Eso reinicia el receptor activo y requiere reconectar; la resolución, FPS y salida se pueden cambiar
por separado (el cambio a Automático no restaura silenciosamente el resto de ajustes del perfil).

#### DEC activo pero salida detenida: comprobar el renderer

En **Ajustes → Pantalla → Salida de vídeo flotante** se puede elegir:

- **Superficie independiente (HDMI)**: predeterminada desde 0.1.1; utiliza SurfaceView y no depende
  de que HWUI repinte una TextureView cuando la actividad está en segundo plano.
- **TextureView (apps)**: ruta alternativa con aceleración de hardware solicitada explícitamente.
- **Lienzo CPU (compatibilidad HDMI)**: experimental desde 0.1.3. Decodifica con el mismo MediaCodec,
  lee una imagen reducida de la GPU fuera de pantalla y la dibuja en un View/Canvas sin aceleración
  de ventana. Evita usar una SurfaceView/TextureView de salida, pero **no elimina la dependencia
  del decodificador ni de la GPU fuera de pantalla**. Limitado a 10 FPS y 480 px por lado; cuesta
  más CPU y memoria que las salidas normales.

Para probar solo la ruta de salida CPU, usar **Controles PiP → Solo salida por lienzo CPU + controles en 10 s**,
cambiar a HDMI durante la cuenta atrás y mantener movimiento en la pantalla emisora.
No se cambia ese ajuste silenciosamente según la marca ni se promete eludir restricciones del firmware.

Las notificaciones de fotogramas del decodificador usan un HandlerThread propio, no el Looper
de la actividad. El panel muestra diagnóstico cada segundo:

- **RX**: fotogramas recibidos por el renderer de la app.
- **DEC**: fotogramas que libera el decodificador; si RX sigue y DEC se detiene, recrear solo la
  ventana no resolverá ese bloqueo. Probar desconectar/reconectar mirroring.
- **OUT**: buffers enviados a EGL (SurfaceView), consumidos por TextureView o dibujados en Canvas
  (CPU). Una salida detenida con decodificación activa genera estado de fallo y recuperación limitada.
- El mensaje distingue también un bloqueo de consumo de la GPU. El último diagnóstico se conserva
  al volver a la portada y los cambios de estado se registran para exportarlos desde Diagnóstico.

**OUT activo no prueba que los píxeles lleguen al panel físico de TCL**. Si RX, DEC y OUT avanzan
pero la imagen permanece quieta en HDMI, comunicar esos valores: podría ser composición del fabricante.
La app verifica continuamente sus buffers, no usa una API privilegiada para detectar la entrada HDMI.

Los cambios de configuración de pantalla recrean solo la salida, manteniendo el receptor y su
decodificador. Los fallos de presentación EGL vuelven a enlazar la superficie; un monitor intenta
recuperar una salida detenida cuando siguen llegando fotogramas (con intentos limitados si no hay
progreso). No reinicia la sesión AirPlay por cada cambio de esquina ni por una pantalla emisora quieta.
Esto **no garantiza** superar restricciones de overlays, contenido protegido, pérdida completa
del contexto GPU o limitaciones de composición HDMI del fabricante. Si persiste, registrar modelo
de TV, Android, backend usado y el log indicado en `docs/TV_TEST_PLAN.md`.

### Cerrar la aplicación

**Cerrar aplicación**, disponible en la barra inferior, los controles de pantalla completa,
el panel flotante y la notificación, detiene receptor, audio y descarga activa; retira ambas ventanas,
elimina la notificación y cierra las tareas de esta app. Desde la app se pide confirmación.
No borra preferencias ni hace un force-stop del proceso. Un nuevo inicio de la app o el arranque
del dispositivo pueden volver a iniciar el receptor según los ajustes existentes.

Al solicitar el modo flotante por primera vez se abre la pantalla de permisos. Después de
concederlo, volver a la app y seleccionar el modo de nuevo. Si la TV no ofrece esa pantalla,
se puede conceder explícitamente desde un host ADB autorizado:

```sh
adb shell appops set io.github.airplaypip SYSTEM_ALERT_WINDOW allow
```

Esto no puede saltarse bloqueos impuestos por el fabricante. El PiP automático al salir de la app
solo usa PiP **del sistema** cuando está disponible; no concede permisos ni crea overlays automáticamente.

## Verificación

```sh
# Sin JDK ni SDK: prueba descarga fijada, preservación de archivos y parches idempotentes.
python3 -m unittest discover -s scripts/tests -v

# Validación específica de taskAffinity, también incluida automáticamente en preBuild:
python3 scripts/validate_manifest.py

# Con el toolchain Android configurado:
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest :app:lintDebug

# Prueba Kotlin enfocada en límites de PiP y reglas de autoentrada:
./gradlew :app:testDebugUnitTest --tests 'io.github.jqssun.airplay.PresentationPolicyTest'

# Movimiento y reglas de detección de salidas congeladas (sin TV):
./gradlew :app:testDebugUnitTest --tests 'io.github.jqssun.airplay.FloatingWindowPolicyTest'

# Diferenciar recepción, decodificación, consumo GPU y salida de la app:
./gradlew :app:testDebugUnitTest --tests 'io.github.jqssun.airplay.VideoHealthTest'

# Selección de software sin fallback silencioso a VPU:
./gradlew :app:testDebugUnitTest --tests 'io.github.jqssun.airplay.VideoDecoderPolicyTest'
```

Antes de considerar estable una versión, ejecutar la matriz de [`docs/TV_TEST_PLAN.md`](docs/TV_TEST_PLAN.md)
en un televisor y emisor Apple reales. El reporte de uso básico no sustituye esa matriz ni valida aún
los cambios de HDMI de 0.1.1.

## Límites y seguridad

- Android **8.0+ (API 26)**. La existencia de Android TV no garantiza PiP nativo ni permiso de overlay.
- Sin activar **Segundo receptor AirPlay**, la instancia principal admite un emisor: «Permitir nuevas conexiones» reemplaza la sesión actual; al desactivarlo, el upstream rechaza al siguiente. Al activarlo aparece una identidad “AirPlay PiP 2” independiente (un emisor por identidad), y se puede elegir layout **Lado a lado** o **Imagen en imagen** y audio de la pantalla seleccionada, primaria, secundaria o silenciado.
- Dual usa dos RAOP servers, decodificadores, superficies y salidas de audio. No implica que un televisor pueda decodificar/componer ambos; la TCL C715 ya presenta problemas con una VPU al entrar HDMI. Comprobar RX/DEC/OUT en ambos streams en una TV real antes de considerarlo estable; usar resoluciones y FPS bajos.
- El receiver no envía eventos de ratón/teclado de vuelta al Mac. La función de teclado/mouse queda fuera de esta versión, como se acordó. Los periféricos emparejados directamente al Mac pueden controlarlo; reenviar el mando de la TV requeriría una app compañera para macOS y permiso explícito de Accesibilidad/Control del ordenador. No es AirPlay Handoff.
- En **PiP**, el modo de decodificador Automático cambia a software H.264 y vuelve al hardware al salir. La transición espera un IDR/IRAP; HEVC y dispositivos sin decoder software compatible no cambian. Es configurable en Ajustes y aún no está validado en la C715.
- No se soporta contenido DRM (por ejemplo, contenido protegido de Apple TV).
- Usar en una red local de confianza. No exponer los puertos AirPlay a internet; PIN es opcional
  y heredado como beta, no una garantía de seguridad para una red hostil.
- La suspensión y los gestores de procesos del fabricante pueden detener el receptor.
  Abrir la app de nuevo es la primera comprobación; no prometer ejecución perpetua en segundo plano.
- Recepción HEVC, latencia, sonido concurrente con otra app y recuperación dependen del hardware;
  necesitan pruebas en la TV objetivo.

## Licencia y fuentes

GPLv3: [`LICENSE`](LICENSE). Procedencia, revisiones y obligaciones de redistribución:
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
El identificador de instalación es `io.github.airplaypip`; el namespace Kotlin y JNI original
se conserva para no romper los símbolos nativos. No hay dependencia de Home Assistant.
