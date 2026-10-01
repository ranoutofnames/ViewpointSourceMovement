@echo off
rem Dedicated test server for Source Movement (config: %USERPROFILE%\Zomboid\Server\SMTest.ini).
rem Same java command as the game's ProjectZomboidServer.bat, plus the ZombieBuddy agent so the mod's
rem Java side (anticheat patches) loads. ZombieBuddy asks here in the console before loading a new or
rem changed Java mod: answer yes for ViewpointSourceMovement. First run also asks for an admin password.
setlocal enableextensions
cd /d "C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid"
SET _JAVA_OPTIONS=
SET PZ_CLASSPATH=./;projectzomboid.jar
".\jre64\bin\java.exe" -agentlib:zbNative=frontend=console --enable-native-access=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED -XX:+UseZGC -XX:-CreateCoredumpOnCrash -XX:-OmitStackTraceInFastThrow -Xmx3072m -Djava.library.path=./natives/;./natives/win64/;./ -cp %PZ_CLASSPATH% zombie.network.GameServer -servername SMTest
PAUSE
