# Validación en TV real

Estado: **matriz pendiente de ejecución**. El usuario confirmó uso básico de 0.1.0 en TV, pero
reportó vídeo congelado con HDMI + ventana flotante mientras el audio continuaba. No se conoce aún
firmware exacto. El modelo reportado es **TCL C715 con Android TV 11**; persiste el fallo en 0.1.2
y el usuario no encontraba los controles. Las fotos posteriores de 0.1.3 se describen abajo;
**la prueba hardware conservadora de 0.1.5 sigue pendiente**. Registrar versión del APK,
emisor/versión iOS o macOS y tipo de red. Un emulador solo sirve para UI y errores de permisos;
no sustituye la validación del protocolo, códecs ni composición con otra app.

Actualización con evidencia del usuario: las fotos de **0.1.3** muestran el panel visible sobre HDMI,
**RX 27–30, DEC 0, OUT 0**, tanto con `surface` como con `software` (salida Canvas).
No confundir esa salida por software con decodificación por software. El usuario confirma que
software en 0.1.4 funciona pero reporta ~5 FPS. La foto posterior en Automático identifica
`OMX.realtek.video.decoder`, 1280×720@24, RX 18 / DEC 0 / OUT 0, `IN=231`, `ERR=1` y
`IllegalStateException` sin mensaje. `state=active` de esa versión no probaba progreso ni que la
excepción fuese de la instancia actual. **No se ha marcado el fallo hardware como resuelto.**

| Caso | Resultado esperado |
| --- | --- |
| Actualizar APK por ADB | 0.1.11 se instala sobre la versión anterior con la misma firma, sin error de análisis y conservando preferencias; verificar en el instalador real/ADB |
| Actualizador LAN con código | Desde otra máquina privada, 4 dígitos, progreso, validación y confirmación de PackageInstaller; quinto código incorrecto cierra esa sesión |
| Actualizador LAN sin código | Solo tras seleccionar explícitamente el modo sin código; un APK con package/signer/version incorrectos sigue rechazado y PackageInstaller sigue requiriendo confirmación |
| Rechazo de APK | APK corrupto, otro packageId, misma/menor versión o certificado distinto se rechaza; no llega a PackageInstaller ni toca la instalación actual |
| Parar servicio de carga | Detener en la app cierra el socket; URL antigua deja de aceptar y el código deja de ser válido |
| Red no confiable | Cliente con IP pública no puede usar endpoint; no probar con el código en Wi-Fi público. HTTP no cifra el código ni el APK |
| Permiso de instalación | Sin «instalar apps desconocidas» se conserva APK validado y se permite abrir Settings; al volver, instalación solo comienza al pulsar el botón de la app |
| Inicio limpio | Receptor arranca; nombre y direcciones reales visibles; D-pad permite alcanzar cada control |
| Permisos denegados | Sin cierres; explicación y ruta para volver a conceder acceso a red si aplica |
| Descubrimiento | iPhone/iPad y Mac encuentran el nombre en la misma subred |
| Dos emisores, dual apagado | Una identidad activa: `allow_new_conn=true` reemplaza la sesión anterior; false rechaza nueva conexión |
| Dos emisores, dual encendido | Descubrir identidades AirPlay distintas, iniciar mirroring simultáneo de dos dispositivos y comprobar que el segundo no reinicia ni mezcla el decoder/Surface del primero |
| Dual lado a lado | Ambos TextureView muestran frames distintos en sus mitades, control/foco funciona con el mando, desconectar uno conserva el otro |
| Dual PiP | Primer emisor ocupa fondo, segundo ocupa ventana inset; ambas superficies y aspect ratios correctos; al desconectar el inset, el fondo sigue vivo |
| Audio dual | Selector pantalla seleccionada/primaria/secundaria/silencio conmuta solo el audio de salida; no cambia conexión ni vídeo de la otra sesión |
| Audio AirPlay a Bluetooth | Con HDMI reproduciendo audio, conectar A2DP, seleccionarlo en Ajustes y confirmar si AirPlay se oye por BT y HDMI continúa. Probar desconexión/reconexión, retorno a predeterminado y anotar qué hace realmente el firmware |
| Foco flotante/Home | Al entrar PiP, menú software conserva foco D-pad; botón Inicio Android o tecla Home devuelve foco a launcher. Probar HOME directo y documentar comportamiento del C715 |
| Decodificador por modo | Automatic + H.264: comprobar decoder HW en fullscreen, SW al entrar system/floating PiP y HW al volver; confirmar espera IDR y tolerancia a decodificador SW no disponible/HEVC |
| Audio AirPlay a Bluetooth | Seleccionar dispositivo A2DP conectado; verificar si AirPlay sale por BT mientras reproducción HDMI conserva sus parlantes. Medir TV/firmware, desconexión del headset y regreso a salida por defecto |
| Decoder automático en PiP | Automatic + H.264: comprobar decoder HW en fullscreen, SW al entrar system/floating PiP y HW al volver; confirmar espera IDR y tolerancia a decodificador SW no disponible/HEVC |
| Recursos duales en TCL C715 | Mantener ambos RX/DEC/OUT en movimiento 10 min, alternar HDMI y registrar FPS, errores, CPU/temperatura y pérdida de frames; detener dual si un stream sufre starvation sostenido |
| Boot dual | Con segundo receptor habilitado y boot start activo, ambas identidades reaparecen con nombres/puertos distintos y no crean duplicados mDNS |
| Mirroring | Imagen de pantalla real y audio, no solo envío de URL; registrar tiempo hasta primer frame |
| Pantalla completa | Entrada automática al comenzar; OK muestra controles también después de 8 segundos; Atrás vuelve sin desconectar |
| PiP nativo disponible | Botón del diálogo entra en PiP; expandir vuelve con imagen; inicio/home conserva sesión visual |
| PiP nativo ausente o bloqueado | No hay excepción ni autoentrada; no se ofrece el botón si falta la función; overlay sigue siendo una opción independiente |
| Permiso overlay denegado | Abre ajustes sin entrar accidentalmente en PiP; al volver, la sesión sigue y permite reintentar |
| Overlay permitido | Ventana visible sobre otra app; el mando sigue controlando esa app; no hay audio duplicado |
| Overlay → fullscreen | Reabrir AirPlay PiP desde el inicio o la acción «Pantalla completa» restaura imagen sin reconectar al emisor |
| Rotación del emisor | Portrait y landscape mantienen proporciones en fullscreen, PiP y overlay; no hay excepción por límites de aspect ratio |
| Posición overlay | Las cuatro esquinas son seleccionables y persisten al reabrir la app |
| HDMI con superficie independiente | Con vídeo del emisor en movimiento, alternar 10 veces entre launcher, HDMI y apps de vídeo; imagen y audio siguen activos; anotar resolución/Hz de cada entrada |
| HDMI con TextureView | Repetir con TextureView; registrar diferencias por TV, aceleración y eventos EGL, sin confundir audio activo con vídeo activo |
| Seleccionar desde HDMI | Acción «Controlar PiP» de notificación o mosaico de Ajustes rápidos selecciona sin abrir MainActivity; cerrar el panel y comprobar borde azul y flechas |
| Acceso del launcher | «Controles PiP» no toma la superficie de vídeo ni abre MainActivity; documentar si el launcher de la TV conserva HDMI detrás al finalizar |
| Liberar mando | OK/Atrás o 15 s sin interacción desactivan el foco del overlay; HDMI vuelve a recibir las flechas |
| Movimiento sin foco | «Siguiente esquina» recorre las cuatro posiciones desde la notificación, también si HDMI/CEC retiene las flechas |
| Cambio de resolución/Hz | Cambiar modo HDMI con una sesión activa; superficie se recrea sin abrir otro receptor/decodificador; comprobar proporción y actualización continua |
| Recuperación y pantalla quieta | Salida congelada con entrada activa produce diagnóstico y recuperación limitada; pantalla emisora sin fotogramas nuevos no dispara recreaciones continuas |
| Retirada seleccionada | Desconectar mientras se mueve PiP elimina ventana, observador de pantalla y temporizador; el mando no queda capturado |
| Controles dentro de la app | Barra inferior y controles fullscreen tienen «Controles PiP» y «Cerrar aplicación», sin depender de un segundo acceso del launcher |
| Control diferido | Elegir «Controles en 10 segundos», cambiar a HDMI y comprobar cuenta atrás y panel independiente; timeout o Atrás libera el foco |
| CPU experimental en TCL | Elegir «Modo CPU TCL/HDMI», probar movimiento/orientación antes y después de HDMI; máximo 10 FPS/480 px por lado, sin otro decodificador ni almacenamiento de imágenes |
| Diagnóstico por etapas | Capturar RX/DEC/OUT y mensaje durante el bloqueo; no interpretar OUT activo como prueba de presentación física en el panel |
| Perfil de decodificador software | Confirmar interrupción de sesión y reinicio; reconectar desde el emisor; verificar H.264, 1280×720/24 anunciado y nombre software real, sin selección silenciosa de códec de hardware |
| Códec software no disponible | Perfil no cambia ajustes si no existe componente compatible; selección estricta manual falla con diagnóstico sin cerrar la app ni usar hardware a escondidas |
| Espera de referencia | Tras error de códec, RX puede continuar con DEC=0; panel muestra waiting_idr, IN/ERR y último error; no reiniciar repetidamente ni prometer recuperación sin reconexión |
| Rendimiento software en TCL | Con stream nuevo, medir DEC/OUT antes y después de HDMI durante 10 min; registrar CPU, temperatura, sincronía A/V y errores, y comparar con Automático |
| Hardware conservador Realtek | Perfil hardware_compat, stream H.264 nuevo y salida SurfaceView; confirmar nombre real, DEC/OUT antes y después de HDMI, sin fallback a software ni claves vendor de latencia |
| Excepción sin mensaje | Obtener operación exacta, gen/inGen/IDRg, edad del error y pila exportada; distinguir configure/start de enqueue/dequeue/release, y no confundir un ERR histórico con el estado del códec actual |
| Referencia después de reinicio | IDRg=0 con entradas aceptadas no prueba que la VPU esté rota: comprobar nuevo IDR desde el emisor; no resetear repetidamente ni reproducir una GOP incompleta |
| Panel independiente | Aunque se congele el vídeo, comprobar si el panel y sus contadores siguen redibujándose, y si permite mover/recrear salida/cerrar |
| Cierre completo | Desde la app y el overlay, cerrar retira vídeo, panel, audio, receptor, notificación y tarea; no reaparece hasta otro inicio/boot configurado; no borra preferencias |
| Desconectar | Ventana flotante desaparece; no quedan audio, wakelocks o superficies huérfanas |
| Reconectar | 10 ciclos de conexión/desconexión, incluidos desde overlay, sin reiniciar la app |
| Red perdida | Cortar/reponer Wi-Fi o Ethernet; registrar si vuelve a anunciarse y si permite conectar de nuevo |
| Sesión prolongada | 30 minutos de mirroring y cambios de modo; registrar congelamientos, retraso A/V y crecimiento de memoria |
| Solo audio | No entra en PiP automáticamente; notificación y controles siguen funcionando |
| Vídeo/HLS | Mantiene reproducción del upstream y usa solo PiP nativo; no transfiere HLS al overlay de mirroring |
| PIN | Activar PIN, conectar un emisor nuevo, comprobar diálogo visible y resultado del emparejamiento |
| Segundo plano/suspensión | Probar Home, standby y reinicio; documentar restricciones del fabricante y permisos necesarios |

Diagnóstico: pestaña **Diagnóstico** (exportar registros), `adb logcat` filtrando
`AirPlayService`, `VideoRenderer`, `VideoPipeline`, `FloatingMirror` y `AndroidRuntime`.
No publicar PIN ni información sensible de la red en reportes públicos.

Captura enfocada para el problema HDMI (iniciar antes de cambiar la entrada, detener después de reproducirlo):

```sh
adb logcat -v threadtime -s FloatingMirror FloatingControls VideoPipeline VideoRenderer AirPlayService AndroidRuntime
```

Buscar `eglSwapBuffers`/`eglMakeCurrent` fallidos, `Recreating output surface only`, el backend y
si aumentan los contadores de entrada/presentación. Un `EGL context lost` completo no es equivalente
a perder solo la superficie de salida y requiere diagnóstico adicional del dispositivo.

No marcar una fila como aprobada a partir de pruebas estáticas o de la mera compilación del APK.
