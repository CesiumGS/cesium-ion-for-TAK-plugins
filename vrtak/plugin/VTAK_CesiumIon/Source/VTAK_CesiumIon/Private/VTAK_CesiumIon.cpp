#include "VTAK_CesiumIon.h"

#include "CesiumIonVrtakActor.h"
#include "Engine/Level.h"
#include "Modules/ModuleManager.h"

IMPLEMENT_MODULE(FVTAK_CesiumIon, VTAK_CesiumIon)
DEFINE_LOG_CATEGORY(LogCesiumIonVrtak);

void FVTAK_CesiumIon::StartupModule()
{
	FWorldDelegates::OnPostWorldInitialization.AddRaw(this, &FVTAK_CesiumIon::HandlePostWorldInitialization);
	FWorldDelegates::OnWorldCleanup.AddRaw(this, &FVTAK_CesiumIon::HandleWorldCleanup);
}

void FVTAK_CesiumIon::ShutdownModule()
{
	FWorldDelegates::OnPostWorldInitialization.RemoveAll(this);
	FWorldDelegates::OnWorldCleanup.RemoveAll(this);
}

static bool IsGameWorld(const UWorld* World)
{
	return World && (World->WorldType == EWorldType::Game || World->WorldType == EWorldType::PIE)
		&& World->PersistentLevel && World->PersistentLevel->GetWorld() == World;
}

void FVTAK_CesiumIon::HandlePostWorldInitialization(UWorld* World, const UWorld::InitializationValues Values)
{
	if (!IsGameWorld(World) || PluginActor.IsValid())
	{
		return;
	}
	FActorSpawnParameters Params;
	Params.SpawnCollisionHandlingOverride = ESpawnActorCollisionHandlingMethod::AlwaysSpawn;
	Params.ObjectFlags |= RF_Transient;
	Params.Name = TEXT("CesiumIonVrtak");
	PluginActor = World->SpawnActor<ACesiumIonVrtakActor>(FVector::ZeroVector, FRotator::ZeroRotator, Params);
	UE_LOG(LogCesiumIonVrtak, Log, TEXT("Cesium ion plugin actor spawned."));
}

void FVTAK_CesiumIon::HandleWorldCleanup(UWorld* World, bool bSessionEnded, bool bCleanupResources)
{
	if (IsGameWorld(World) && PluginActor.IsValid())
	{
		PluginActor->Destroy();
		PluginActor.Reset();
	}
}
