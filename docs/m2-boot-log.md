# Milestone 2 – first boot of Maldita Castilla's 32-bit engine

Output of `engine-build/yoyo_boot Maldita_Castilla.apk` on a 64-bit x86 Linux machine (dynarmic JIT).
The engine loads `game.droid`, initialises GameMaker and stops at its first OpenGL call (`glGetError`) – the start of milestone 3.

```
loaded libopenal.so (lib/armeabi-v7a/)
loaded libyoyo.so (lib/armeabi-v7a/)
running 1 constructors of libopenal.so
running 20 constructors of libyoyo.so
GetStaticObjectField (returning 0)
JNI_OnLoad returned 0x10004
calling GameMaker Startup…
[I/yoyo] 
[I/yoyo] ***************************************
[I/yoyo] *   YoYo Games Android Runner V0.1    *
[I/yoyo] ***************************************	 
[I/yoyo] Importing GL
game dlopen("libGLESv2.so")
[I/yoyo] ... attemping to import GL2
[I/yoyo] Finished importing GL
[I/yoyo] Loading APK <game.apk>Just before strcpy
[I/yoyo] CommandLine: -game game.droid
[I/yoyo] MemoryManager allocated: 16768
[I/yoyo] Finished Init
[I/yoyo] RunnerLoadGame: game.droid
[I/yoyo] RunnerLoadGame() - assets/game.droid
[I/yoyo] Checking if INIFile assets/options.ini Exists
[I/yoyo] _FileExists fail in zip - assets/options.ini
[I/yoyo] !!!!No INI File assets/options.ini
[I/yoyo] _FileExists fail in zip - assets/game.yydebug
[I/yoyo] Reading File assets/game.droid
[I/yoyo] Loaded File assets/game.droid
[I/yoyo] IFF wad found
[I/yoyo] Get Resolution
[I/yoyo] Get Header Information
[I/yoyo] InitGMLFunctions
[I/yoyo] HighScore..Game..Math..Graphic..Action..File..Resource..Interaction..3D..Particle..Misc..DS..Sound..DPlay..Physics..Gamepad..GAMEPAD: 2 axis values (last) at 0x42f5abf0
[I/yoyo] Buffers..Networking..Shaders..YoYo..Fini
[I/yoyo] Code_Load()
[I/yoyo] VARI_Load()
[I/yoyo] ID_STRG
[I/yoyo] Create Window
[I/yoyo] Init Graphics
[I/yoyo] GR_D3D_Init()
STOP: reached the first graphics call: glGetError
MILESTONE 2 REACHED: the engine booted and asked for graphics.
reached the first graphics call: glGetError
```
