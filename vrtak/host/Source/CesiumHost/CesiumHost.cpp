// A host module so UnrealBuildTool can build Cesium for Unreal with
// VR-TAK's engine; the Cesium ion plugin for VR-TAK starts from here.
#include "Modules/ModuleManager.h"

IMPLEMENT_PRIMARY_GAME_MODULE(FDefaultGameModuleImpl, CesiumHost, "CesiumHost");
