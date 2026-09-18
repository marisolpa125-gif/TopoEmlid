# Topo Emlid v0.3 — generar el APK con GitHub Actions

Esta versión está preparada para que GitHub compile el APK de prueba en la nube. No es necesario instalar Android Studio en la computadora.

## Generar el APK

1. Abra el repositorio en GitHub.
2. Entre en **Actions**.
3. Seleccione **Generar APK de prueba**.
4. Pulse **Run workflow**.
5. Abra la ejecución terminada.
6. En **Artifacts**, descargue `TopoEmlid-v0.3-debug`.
7. Descomprima ese archivo. Dentro estará `TopoEmlid-v0.3-debug.apk`.

Ese APK se puede enviar por correo, Drive, WhatsApp u otro medio a la tablet Android para instalarlo.

## Importante

Este es un APK **debug**, pensado para pruebas. Cuando la aplicación esté lista para producción, se deberá generar una versión **release** firmada.
