# Herramientas de compilación necesarias

| Herramienta       | Versión mínima | Notas                                          |
| ----------------- | -------------- | ---------------------------------------------- |
| **JDK**           | 17             | Se requiere JDK 17+ (compatible con bytecode). |
| **Gradle**        | 9.5.1          | Se usa el wrapper (`gradlew`) incluido.        |
| **Android SDK**   | 36             | `compileSdk` y `targetSdk`.                    |
| **Android NDK**   | 26.1.10909125  | Necesario para librerías nativas.              |
| **Kotlin**        | (vía plugin)   | Incluido en el plugin de Gradle.               |
| **Command Line Tools** | Última        | Para aceptar licencias y gestionar SDK.        |

## Variables de entorno recomendadas

```bash
export ANDROID_HOME=$HOME/Android/Sdk
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/26.1.10909125
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
```

## Verificación rápida

```bash
java -version
./gradlew --version
./gradlew assembleDebug
```
