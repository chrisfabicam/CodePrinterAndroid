# CodePrinter Android

Dos apps Android para consultar artículos en el ERP y imprimir etiquetas TSPL2
en una impresora térmica conectada por USB a un celular.

## Arquitectura

```
┌─────────────────────────┐         ┌──────────────────────────────┐
│ App Operador            │         │ App PrintServer              │
│ (cualquier celular)     │  HTTP   │ (celular con impresora USB)  │
│                         │ ──────► │                              │
│ - Busca artículos       │  :8080  │ - Recibe TSPL2 crudo         │
│ - Muestra precios/stock │         │ - Lo envía por USB           │
│ - Arma TSPL2            │         │ - Muestra su IP en pantalla  │
└─────────────────────────┘         └──────────────────────────────┘
        │                                    │
        ▼                                    ▼
   Servidor ERP                        Impresora TSPL2
   (SOAP sobre HTTP)                   (USB OTG)
```

- **Operador**: consulta el ERP vía SOAP, muestra el artículo y envía el TSPL2
  al celular-impresora.
- **PrintServer**: corre un servidor HTTP mínimo (NanoHTTPD) en el puerto 8080
  y escribe los bytes recibidos en la impresora por USB.

## Estructura

```
.
├── .github/workflows/build.yml   # CI: compila ambos APKs en GitHub Actions
├── operador/                     # Proyecto Gradle independiente
│   ├── gradlew
│   ├── settings.gradle.kts
│   ├── build.gradle.kts
│   └── app/
└── printserver/                  # Proyecto Gradle independiente
    ├── gradlew
    ├── settings.gradle.kts
    ├── build.gradle.kts
    └── app/
```

## Compilar

### Opción 1: GitHub Actions (recomendado)

1. Hacé push a `main`/`master` o lanzá el workflow manualmente desde la
   pestaña **Actions**.
2. Al terminar, descargá los artifacts:
   - `operador-debug-apk`
   - `printserver-debug-apk`

### Opción 2: Local

Requisitos: JDK 17, Android SDK (platform 34 + build-tools 34.0.0).

```bash
# Operador
cd operador
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk

# PrintServer
cd ../printserver
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Instalación

1. Instalá `printserver-debug-apk` en el celular que tiene la impresora
   conectada por USB.
2. Instalá `operador-debug-apk` en el/los celulares de los operarios.
3. Ambos celulares deben estar en la **misma red WiFi**.

## Configuración

### App PrintServer

1. Abrí la app.
2. Conectá la impresora por USB (Android pedirá permiso la primera vez).
3. La app muestra la **IP local** del celular (ej: `192.168.1.50:8080`).
4. Dejá la app abierta (corre como servicio en primer plano).

### App Operador

1. Abrí la app.
2. Tocá **⚙️ Ajustes** y completá:
   - **Endpoint SOAP del ERP**: URL completa del servicio
     (ej: `http://<ip-o-host>/PSIGServices/PSIGServicios.svc`)
   - **Header Host del ERP**: hostname interno que exige IIS
   - **URL del celular-impresora**: la IP que muestra la app PrintServer
     (ej: `http://192.168.1.50:8080`)
3. Los valores se guardan en `SharedPreferences` (solo en ese dispositivo).
4. Podés cambiarlos en cualquier momento sin reiniciar la app.

## Uso

1. Ingresá o escaneá el código del artículo.
2. Elegí el tipo: **Código artículo** o **Código de barras**.
3. Tocá **Consultar**.
4. Revisá el artículo, precios y stock.
5. Indicá la **cantidad** (default 1).
6. Tocá **Imprimir**.

## Seguridad

- **No hay datos sensibles en el código fuente.** El endpoint del ERP, el
  header `Host` y la URL de la impresora se configuran en la app y se guardan
  en `SharedPreferences` con `Context.MODE_PRIVATE`.
- `local.properties`, keystores y APKs están en `.gitignore`.
- Para publicar en Play Store se recomienda agregar autenticación (pendiente).

## Licencia

Uso interno — HERRACOR S.A.
