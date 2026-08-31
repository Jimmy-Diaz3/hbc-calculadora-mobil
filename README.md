# Calculadora HBC Mobile v2.5 Cloud

APK Android de la Calculadora HBC con las reglas preexistentes de cálculo, una sola carpeta compartida en Google Drive y la entidad 2521: COOP. DE AHORRO Y CREDITO INCLUSIVA (INCLUSIVA).

## Uso

1. Instale la APK y elija **Vincular carpeta CALCULADORA HBC**.
2. En el selector de Android, seleccione exactamente la carpeta `CALCULADORA HBC` de Google Drive y permita lectura/escritura.
3. La aplicación detecta automáticamente:
   - `DATA PLH/CAS` y `DATA PLH/NOMBRADOS`.
   - `DATA BANCOS`, usando el último periodo con PRN/TXT válidos.
   - `DATA CAFAE - EXCEL`, usando el último periodo válido disponible.
   - `DATA PRESTAMOS/operaciones_remuneraciones.json`.

Las aprobaciones y anulaciones se fusionan con el historial compartido antes de guardarse. El teléfono conserva una caché local para consulta; borrar esa caché nunca elimina datos de Drive. PDF, Excel y reportes siguen siendo funciones de la versión PC y se guardan localmente allí.
