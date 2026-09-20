# Topo Emlid v0.3 — prototipo Android

## Descargar APK directo

**[DESCARGAR TopoEmlid-v0.3-debug.apk](https://github.com/marisolpa125-gif/TopoEmlid/releases/download/topoemlid-latest-debug/TopoEmlid-v0.3-debug.apk)**

> Importante: el archivo correcto pesa aproximadamente 60.9 MB y termina en `.apk`.  
> Si GitHub ofrece `TopoEmlid-v0.3-debug.zip` de unos 30.45 MB, está entrando a **Actions**; ese no es el enlace de descarga directa.

Proyecto inicial de una libreta topográfica Android orientada al trabajo con receptores Emlid Reach.

## Incluido en esta versión

- Estructura Android en Kotlin + Jetpack Compose.
- Panel inicial de estado GNSS/NMEA.
- Configuración de perfiles NTRIP.
- Sistemas de coordenadas previstos: CRTM05, CR-SIRGAS y WGS84.
- Configuración vertical con EGM2008 o archivo geoidal local seleccionado desde Android.
- Base del motor geométrico para rectángulos, triángulos y círculos.
- Base para capas y mapa/CAD.
- GitHub Actions para generar automáticamente un APK debug sin instalar Android Studio.

## Compilar en GitHub

Consulte `GUIA-GITHUB-ACTIONS.md`.

## Próximos módulos previstos

- Mapa real con MapLibre.
- WMS, WMTS, XYZ/TMS y otras fuentes de mapa.
- Importación de DXF, KML/KMZ, GeoJSON, SHP y CSV.
- Conexión Emlid por Bluetooth/NMEA.
- Lectura e interpolación de archivos geoidales locales.
- COGO, replanteo y división de polígonos.

> Aviso: esta versión es un prototipo técnico de prueba; no debe utilizarse todavía como única fuente para decisiones topográficas o catastrales de producción.
