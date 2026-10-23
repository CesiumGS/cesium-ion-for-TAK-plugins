#pragma once

#include "CoreMinimal.h"
#include "Engine/World.h"
#include "Modules/ModuleInterface.h"
#include "UObject/WeakObjectPtr.h"

DECLARE_LOG_CATEGORY_EXTERN(LogCesiumIonVrtak, Log, All);

class ACesiumIonVrtakActor;

/**
 * The Cesium ion plugin for VR-TAK. Like VR-TAK's example plugin, the module
 * spawns its plugin actor in each game world (standalone or PIE) and
 * destroys it when the world is cleaned up.
 */
class FVTAK_CesiumIon : public IModuleInterface
{
public:
	virtual void StartupModule() override;
	virtual void ShutdownModule() override;

private:
	void HandlePostWorldInitialization(UWorld* World, const UWorld::InitializationValues Values);
	void HandleWorldCleanup(UWorld* World, bool bSessionEnded, bool bCleanupResources);

	TWeakObjectPtr<ACesiumIonVrtakActor> PluginActor;
};
