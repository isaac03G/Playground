@echo off
rem ============================================================================
rem  Mantiene el catalogo al dia SIN intervencion manual (cada 6 horas):
rem    - refresca las tasas de cambio
rem    - revisa los precios de los juegos guardados y detecta ofertas
rem    - agrega juegos nuevos
rem
rem  Dejalo abierto (minimizado) o programalo para que arranque solo al iniciar
rem  sesion: Programador de tareas de Windows > Crear tarea basica > "Al iniciar
rem  sesion" > Iniciar un programa > este archivo.
rem
rem  Cada pasada revisa TODOS los juegos guardados (--lote 100000) y vuelve a revisar los que tengan mas de 5 h
rem  sin revisarse (--antiguedad 5, menor que el ciclo de 6 h, asi ninguno se queda por fuera).
rem
rem  Se pueden pasar opciones extra, p. ej.:  iniciar_actualizador.bat --cada 3 --lote 100
rem  (--cada de la linea de comandos reemplaza al de abajo)
rem ============================================================================
chcp 65001 >nul
cd /d "%~dp0"

rem Usa primero el entorno virtual local y, si no existe, Python disponible en PATH.
set "PY=%~dp0.venv\Scripts\python.exe"
if not exist "%PY%" set "PY=python"

title Playground - actualizador automatico
"%PY%" actualizador.py --cada 6 --con-tasas --lote 100000 --antiguedad 5 %*
echo.
echo El actualizador se detuvo. Revisa el mensaje de arriba.
pause
