#include "CesiumIonVrtakActor.h"

#include "CesiumIonVrtakPanel.h"
#include "CesiumIonVrtakSession.h"
#include "VTAK_CesiumIon.h"

#include "Cesium3DTileset.h"
#include "Cesium3DTilesetLoadFailureDetails.h"
#include "CesiumRasterOverlayLoadFailureDetails.h"
#include "Camera/PlayerCameraManager.h"
#include "CesiumGeoreference.h"
#include "CesiumIonRasterOverlay.h"
#include "CesiumIonServer.h"
#include "Blueprint/UserWidget.h"
#include "Blueprint/WidgetTree.h"
#include "Blueprint/WidgetLayoutLibrary.h"
#include "Components/Button.h"
#include "Components/TextBlock.h"
#include "UObject/UObjectIterator.h"
#include "Engine/Engine.h"
#include "Components/WidgetComponent.h"
#include "Components/WidgetInteractionComponent.h"
#include "Engine/GameInstance.h"
#include "Subsystems/GameInstanceSubsystem.h"
#include "Subsystems/WorldSubsystem.h"
#include "Engine/GameViewportClient.h"
#include "EngineUtils.h"
#include "GameFramework/Pawn.h"
#include "GameFramework/PlayerController.h"
#include "UObject/UnrealType.h"
#include "Misc/CommandLine.h"
#include "Misc/Parse.h"
#include "Misc/Paths.h"
#include "UnrealClient.h"
THIRD_PARTY_INCLUDES_START
#include <Cesium3DTilesSelection/BoundingVolume.h>
#include <Cesium3DTilesSelection/Tile.h>
#include <Cesium3DTilesSelection/Tileset.h>
THIRD_PARTY_INCLUDES_END
#include "VH_GeodeticCoord.h"
#include "VH_GlobeReferenceActor.h"
#include "VH_GlobeReferenceActor_ENU.h"

namespace
{
	/** VR-TAK's tile manager (Game_TileManager), found by reflection: no compile dependency on VR-TAK's Game module. */
	UObject* FindTileManager()
	{
		UClass* TileManagerClass = FindFirstObject<UClass>(TEXT("Game_TileManager"), EFindFirstObjectOptions::None);
		if (!TileManagerClass)
		{
			return nullptr;
		}
		for (TObjectIterator<UObject> It; It; ++It)
		{
			if (It->IsA(TileManagerClass) && !It->HasAnyFlags(RF_ClassDefaultObject) && IsValid(*It))
			{
				return *It;
			}
		}
		return nullptr;
	}

	bool IsTileManagerEnabled(UObject* TileManager, bool& bEnabled)
	{
		UFunction* Function = TileManager->FindFunction(TEXT("IsEnabled"));
		struct { bool ReturnValue = false; } Params;
		if (!Function || Function->ParmsSize != sizeof(Params))
		{
			return false;
		}
		TileManager->ProcessEvent(Function, &Params);
		bEnabled = Params.ReturnValue;
		return true;
	}

	bool SetTileManagerEnabled(UObject* TileManager, bool bEnabled)
	{
		UFunction* Function = TileManager->FindFunction(TEXT("SetEnabled"));
		struct { bool bIsEnabled; } Params{bEnabled};
		if (!Function || Function->ParmsSize != sizeof(Params))
		{
			return false;
		}
		TileManager->ProcessEvent(Function, &Params);
		return true;
	}

	/** VR-TAK's ENU globe uses this altitude to mean "on the ground". */
	constexpr double GroundSentinel = 9999999.0;
	constexpr double MeasureMetres = 100.0;
	constexpr double MetresPerDegreeLatitude = 111320.0;

	FGeodeticCoord3D Geodetic(double Longitude, double Latitude, double Altitude)
	{
		FGeodeticCoord3D Coord;
		Coord.Longitude = Longitude;
		Coord.Latitude = Latitude;
		Coord.Altitude = Altitude;
		return Coord;
	}

	bool ParseNumbers(const TCHAR* Switch, TArray<double>& Out)
	{
		FString Value;
		// Lists are comma-separated, so read past commas.
		if (!FParse::Value(FCommandLine::Get(), Switch, Value, /*bShouldStopOnSeparator*/ false))
		{
			return false;
		}
		TArray<FString> Parts;
		Value.ParseIntoArray(Parts, TEXT(","), true);
		for (const FString& Part : Parts)
		{
			Out.Add(FCString::Atod(*Part));
		}
		return Out.Num() > 0;
	}
}

ACesiumIonVrtakActor::ACesiumIonVrtakActor()
{
	PrimaryActorTick.bCanEverTick = true;
	PrimaryActorTick.TickInterval = 0.25f;
}

namespace
{
	/** Calls UFUNCTION FunctionName on Target with one object (or class) argument; returns its object result, if any. */
	UObject* CallWithObject(UObject* Target, const TCHAR* FunctionName, UObject* Argument)
	{
		UFunction* Function = Target ? Target->FindFunction(FunctionName) : nullptr;
		if (!Function)
		{
			return nullptr;
		}
		TArray<uint8> Params;
		Params.SetNumZeroed(Function->ParmsSize);
		for (TFieldIterator<FProperty> It(Function); It && It->HasAnyPropertyFlags(CPF_Parm); ++It)
		{
			It->InitializeValue_InContainer(Params.GetData());
		}
		FObjectPropertyBase* Return = nullptr;
		bool bArgumentSet = false;
		for (TFieldIterator<FProperty> It(Function); It && It->HasAnyPropertyFlags(CPF_Parm); ++It)
		{
			FObjectPropertyBase* Object = CastField<FObjectPropertyBase>(*It);
			if (!Object)
			{
				continue;
			}
			if (It->HasAnyPropertyFlags(CPF_ReturnParm))
			{
				Return = Object;
			}
			else if (!bArgumentSet)
			{
				Object->SetObjectPropertyValue_InContainer(Params.GetData(), Argument);
				bArgumentSet = true;
			}
		}
		Target->ProcessEvent(Function, Params.GetData());
		UObject* Result = Return ? Return->GetObjectPropertyValue_InContainer(Params.GetData()) : nullptr;
		for (TFieldIterator<FProperty> It(Function); It && It->HasAnyPropertyFlags(CPF_Parm); ++It)
		{
			It->DestroyValue_InContainer(Params.GetData());
		}
		return Result;
	}

	/** The actor class VR-TAK's UI subsystem uses for world panels (its default, or one of its per-widget settings). */
	UClass* FindWorldUiActorClass(UObject* Core)
	{
		UClass* Found = nullptr;
		if (FStructProperty* Default = Core ? FindFProperty<FStructProperty>(Core->GetClass(), TEXT("DefaultWorldSettings")) : nullptr)
		{
			if (FObjectPropertyBase* ClassProperty = FindFProperty<FObjectPropertyBase>(Default->Struct, TEXT("WorldUIActorClass")))
			{
				Found = Cast<UClass>(ClassProperty->GetObjectPropertyValue_InContainer(Default->ContainerPtrToValuePtr<void>(Core)));
			}
		}
		UE_LOG(LogCesiumIonVrtak, Log, TEXT("VR-TAK's default world UI actor class: %s."), Found ? *Found->GetName() : TEXT("none"));
		if (!Found)
		{
			if (FMapProperty* Map = FindFProperty<FMapProperty>(Core->GetClass(), TEXT("DefaultClassWorldSettings")))
			{
				FScriptMapHelper Helper(Map, Map->ContainerPtrToValuePtr<void>(Core));
				FStructProperty* ValueProperty = CastField<FStructProperty>(Map->ValueProp);
				FObjectPropertyBase* ClassProperty = ValueProperty ? FindFProperty<FObjectPropertyBase>(ValueProperty->Struct, TEXT("WorldUIActorClass")) : nullptr;
				UE_LOG(LogCesiumIonVrtak, Log, TEXT("VR-TAK has per-widget world settings for %d widget classes."), Helper.Num());
				for (int32 Index = 0; ClassProperty && Index < Helper.GetMaxIndex(); ++Index)
				{
					if (Helper.IsValidIndex(Index))
					{
						UClass* Each = Cast<UClass>(ClassProperty->GetObjectPropertyValue_InContainer(Helper.GetValuePtr(Index)));
						if (Each && !Found)
						{
							Found = Each;
							UE_LOG(LogCesiumIonVrtak, Log, TEXT("Using world UI actor class %s from a per-widget setting."), *Each->GetName());
						}
					}
				}
			}
		}
		return Found;
	}

	/** VR-TAK's UI subsystem, OpenInWorldByClass: a floating panel Size pixels across, Offset (cm) in front of the camera. */
	UObject* OpenInWorldWithSettings(UObject* Core, UClass* WidgetClass, UClass* ActorClass, const FVector2D& Size, const FVector& Offset)
	{
		UFunction* Function = Core ? Core->FindFunction(TEXT("OpenInWorldByClass")) : nullptr;
		if (!Function)
		{
			UE_LOG(LogCesiumIonVrtak, Warning, TEXT("VR-TAK's UI subsystem has no OpenInWorldByClass."));
			return nullptr;
		}
		TArray<uint8> Params;
		Params.SetNumZeroed(Function->ParmsSize);
		for (TFieldIterator<FProperty> It(Function); It && It->HasAnyPropertyFlags(CPF_Parm); ++It)
		{
			It->InitializeValue_InContainer(Params.GetData());
		}
		if (FObjectPropertyBase* ClassProperty = FindFProperty<FObjectPropertyBase>(Function, TEXT("userWidgetClass")))
		{
			ClassProperty->SetObjectPropertyValue_InContainer(Params.GetData(), WidgetClass);
		}
		if (FStructProperty* World = FindFProperty<FStructProperty>(Function, TEXT("worldSettings")))
		{
			void* Settings = World->ContainerPtrToValuePtr<void>(Params.GetData());
			if (ActorClass)
			{
				if (FObjectPropertyBase* ActorClassProperty = FindFProperty<FObjectPropertyBase>(World->Struct, TEXT("WorldUIActorClass")))
				{
					ActorClassProperty->SetObjectPropertyValue_InContainer(Settings, ActorClass);
				}
			}
			if (FStructProperty* SizeProperty = FindFProperty<FStructProperty>(World->Struct, TEXT("Size")))
			{
				*SizeProperty->ContainerPtrToValuePtr<FVector2D>(Settings) = Size;
			}
			if (FStructProperty* OffsetProperty = FindFProperty<FStructProperty>(World->Struct, TEXT("LocalSpawnOffset")))
			{
				*OffsetProperty->ContainerPtrToValuePtr<FVector>(Settings) = Offset;
			}
		}
		Core->ProcessEvent(Function, Params.GetData());
		UObject* Result = nullptr;
		for (TFieldIterator<FProperty> It(Function); It && It->HasAnyPropertyFlags(CPF_Parm); ++It)
		{
			if (It->HasAnyPropertyFlags(CPF_ReturnParm))
			{
				if (FObjectPropertyBase* Return = CastField<FObjectPropertyBase>(*It))
				{
					Result = Return->GetObjectPropertyValue_InContainer(Params.GetData());
				}
			}
		}
		for (TFieldIterator<FProperty> It(Function); It && It->HasAnyPropertyFlags(CPF_Parm); ++It)
		{
			It->DestroyValue_InContainer(Params.GetData());
		}
		UE_LOG(LogCesiumIonVrtak, Log, TEXT("VR-TAK's UI subsystem OpenInWorldByClass returned %s."),
			Result ? *Result->GetClass()->GetName() : TEXT("nothing"));
		return Result;
	}

	void SetStringProperty(UObject* Object, const TCHAR* Name, const FString& Value)
	{
		if (FStrProperty* Property = FindFProperty<FStrProperty>(Object->GetClass(), Name))
		{
			Property->SetPropertyValue_InContainer(Object, Value);
		}
	}
}

void ACesiumIonVrtakActor::BeginPlay()
{
	Super::BeginPlay();
	TilesetFailureHandle = OnCesium3DTilesetLoadFailure.AddUObject(this, &ACesiumIonVrtakActor::OnTilesetLoadFailure);
	OverlayFailureHandle = OnCesiumRasterOverlayLoadFailure.AddUObject(this, &ACesiumIonVrtakActor::OnOverlayLoadFailure);
	Session = NewObject<UCesiumIonVrtakSession>(this);
	Session->Load();
	if (Session->IsSelfHosted())
	{
		SelfHostedServer = NewObject<UCesiumIonServer>(this);
		SelfHostedServer->DisplayName = Session->Describe();
		SelfHostedServer->ServerUrl = Session->GetServerUrl();
		SelfHostedServer->ApiUrl = Session->GetApiUrl();
	}

	FActorSpawnParameters Params;
	Params.ObjectFlags |= RF_Transient;
	Params.Name = TEXT("CesiumIonVrtakGeoreference");
	Georeference = GetWorld()->SpawnActor<ACesiumGeoreference>(Params);
	if (Georeference)
	{
		Georeference->SetOriginPlacement(EOriginPlacement::CartographicOrigin);
	}

	ApplyTestSwitches();
	SyncGeoreference();
	OpenPanel();
	RefreshAssets();
}

void ACesiumIonVrtakActor::EndPlay(const EEndPlayReason::Type Reason)
{
	OnCesium3DTilesetLoadFailure.Remove(TilesetFailureHandle);
	OnCesiumRasterOverlayLoadFailure.Remove(OverlayFailureHandle);
	for (TPair<int64, ACesium3DTileset*>& Entry : Tilesets)
	{
		if (IsValid(Entry.Value))
		{
			Entry.Value->Destroy();
		}
	}
	Tilesets.Reset();
	Overlays.Reset();
	UpdateVrtakTerrain(/*bShuttingDown*/ true);
	if (IsValid(Georeference))
	{
		Georeference->Destroy();
	}
	if (bPanelInWorld)
	{
		ClosePanelInWorld();
	}
	if (Panel)
	{
		Panel->RemoveFromParent();
	}
	if (RibbonButton)
	{
		CallWithObject(FindWorldSubsystem(TEXT("/Script/Game.Game_Subsystem_Ribbon")), TEXT("UnregisterButton"), RibbonButton);
		RibbonButton = nullptr;
	}
	Super::EndPlay(Reason);
}

void ACesiumIonVrtakActor::ApplyTestSwitches()
{
	TArray<double> Origin;
	if (ParseNumbers(TEXT("CesiumIonOrigin="), Origin) && Origin.Num() >= 2)
	{
		// Applied in the TOC just before entering the world (see Tick).
		TestOrigin = Origin;
		TestCamera = { Origin[0] - 0.010, Origin[1], 350.0, 0.0, -35.0 };
	}
	TArray<double> Raise;
	if (ParseNumbers(TEXT("CesiumIonRaise="), Raise))
	{
		TestRaiseMetres = Raise[0];
		Message(FString::Printf(TEXT("Test: Cesium layers raised %.0f m."), TestRaiseMetres));
	}
	TArray<double> Camera;
	if (ParseNumbers(TEXT("CesiumIonCamera="), Camera) && Camera.Num() >= 5)
	{
		TestCamera = Camera;
	}
	TArray<double> EnterWorld;
	if (ParseNumbers(TEXT("CesiumIonEnterWorld="), EnterWorld))
	{
		EnterWorldAt = EnterWorld[0];
		bAnswerTutorialPrompt = true;
	}
	TArray<double> Settings;
	if (ParseNumbers(TEXT("CesiumIonShowSettings="), Settings))
	{
		ShowSettingsAt = Settings[0];
		// Open across the next screenshot (they are 30 s apart), closed before the one after.
		HideSettingsAt = Settings[0] + 25.0;
	}
	bStartSignIn = FParse::Param(FCommandLine::Get(), TEXT("CesiumIonSignIn"));
	TArray<double> Seconds;
	if (ParseNumbers(TEXT("CesiumIonScreenshots="), Seconds))
	{
		ScreenshotAt = Seconds[0];
	}
	Seconds.Reset();
	if (ParseNumbers(TEXT("CesiumIonQuitAfter="), Seconds))
	{
		QuitAt = Seconds[0];
	}
	TArray<double> Ids;
	if (ParseNumbers(TEXT("CesiumIonLoad="), Ids))
	{
		for (double Id : Ids)
		{
			PendingTestLoads.Add((int64)Id);
		}
	}
}

bool ACesiumIonVrtakActor::TeleportPlayer(double Latitude, double Longitude, double Height, double Bearing, double Pitch, bool bTurn)
{
	APlayerController* Player = GetWorld()->GetFirstPlayerController();
	APawn* Pawn = Player ? Player->GetPawn() : nullptr;
	UFunction* Teleport = Pawn ? Pawn->FindFunction(TEXT("TeleportToCoordinate")) : nullptr;
	FStructProperty* Coordinate = Teleport ? CastField<FStructProperty>(Teleport->FindPropertyByName(TEXT("coordinate"))) : nullptr;
	if (!Coordinate || Coordinate->Struct != FGeodeticCoord3D::StaticStruct())
	{
		UE_LOG(LogCesiumIonVrtak, Warning, TEXT("VR-TAK's pawn has no TeleportToCoordinate(FGeodeticCoord3D); the view was not moved."));
		return false;
	}
	TArray<uint8> Params;
	Params.SetNumZeroed(Teleport->ParmsSize);
	for (TFieldIterator<FProperty> It(Teleport); It && It->HasAnyPropertyFlags(CPF_Parm); ++It)
	{
		It->InitializeValue_InContainer(Params.GetData());
	}
	*Coordinate->ContainerPtrToValuePtr<FGeodeticCoord3D>(Params.GetData()) = Geodetic(Longitude, Latitude, Height);
	Pawn->ProcessEvent(Teleport, Params.GetData());
	for (TFieldIterator<FProperty> It(Teleport); It && It->HasAnyPropertyFlags(CPF_Parm); ++It)
	{
		It->DestroyValue_InContainer(Params.GetData());
	}
	if (bTurn)
	{
		// VR-TAK's world: +X is north, so the bearing is the yaw.
		const FRotator View(Pitch, Bearing, 0.0);
		Player->SetControlRotation(View);
		Pawn->SetActorRotation(FRotator(0.0, Bearing, 0.0));
	}
	UE_LOG(LogCesiumIonVrtak, Log, TEXT("Moved the view to %.5f, %.5f, %.0f m (as VR-TAK's Go To does)%s."),
		Latitude, Longitude, Height, bTurn ? *FString::Printf(TEXT(", bearing %.0f, pitch %.0f"), Bearing, Pitch) : TEXT(""));
	return true;
}

void ACesiumIonVrtakActor::EnsurePanel()
{
	if (bPanelInWorld || !bPanelWanted)
	{
		return;
	}
	if (!Panel)
	{
		OpenPanel();
		return;
	}
	PlacePanel();
	if (!Panel->IsInViewport())
	{
		Panel->AddToViewport(50);
		PanelSize = FVector2D::ZeroVector;
		PlacePanel();
		if (!bPanelRestoreLogged)
		{
			bPanelRestoreLogged = true;
			UE_LOG(LogCesiumIonVrtak, Log, TEXT("Cesium ion panel was removed from the screen; added again."));
		}
	}
}

AVH_GlobeReferenceActor* ACesiumIonVrtakActor::FindGlobe() const
{
	// VR-TAK has several globe actors: the TOC's tabletop ENU map (scaled down), the
	// world's full-size Ellipsoid globe (Earth-centred), and some not set up yet. Use the
	// set-up globe that puts the player nearest the ground: the tabletop in the TOC,
	// the Ellipsoid globe in the world.
	APlayerController* Player = GetWorld()->GetFirstPlayerController();
	const APawn* Pawn = Player ? Player->GetPawn() : nullptr;
	const FVector PlayerLocation = Pawn ? Pawn->GetActorLocation() : FVector::ZeroVector;
	AVH_GlobeReferenceActor* Best = nullptr;
	AVH_GlobeReferenceActor* First = nullptr;
	double BestHeight = TNumericLimits<double>::Max();
	FString Candidates;
	for (TActorIterator<AVH_GlobeReferenceActor> It(GetWorld()); It; ++It)
	{
		First = First ? First : *It;
		AVH_GlobeReferenceActor_ENU* Enu = Cast<AVH_GlobeReferenceActor_ENU>(*It);
		const FGeodeticCoord3D Origin = Enu ? Enu->GetLocation() : FGeodeticCoord3D();
		const bool bOriginSet = !Enu || (FMath::Abs(Origin.Latitude) <= 90.0 && FMath::Abs(Origin.Longitude) <= 180.0
			&& (FMath::Abs(Origin.Altitude) < 100000.0 || FMath::IsNearlyEqual(Origin.Altitude, GroundSentinel, 1.0)));
		const FGeodeticCoord3D AtPlayer = It->WorldToGeodeticDouble(PlayerLocation);
		const bool bMapsPlayer = FMath::IsFinite(AtPlayer.Latitude) && FMath::IsFinite(AtPlayer.Longitude)
			&& FMath::IsFinite(AtPlayer.Altitude) && FMath::Abs(AtPlayer.Latitude) <= 90.0 && FMath::Abs(AtPlayer.Longitude) <= 180.0;
		const bool bSetUp = bOriginSet && bMapsPlayer && !It->GetActorScale3D().IsNearlyZero();
		Candidates += FString::Printf(TEXT(" %s%s(%s, scale %.2f, player at %.4f, %.4f, %.0f m)"),
			*It->GetName(), bSetUp ? TEXT("") : TEXT(" [not set up]"), Enu ? TEXT("ENU") : TEXT("ellipsoid"),
			It->GetActorScale3D().X, AtPlayer.Latitude, AtPlayer.Longitude, AtPlayer.Altitude);
		if (bSetUp && FMath::Abs(AtPlayer.Altitude) < BestHeight)
		{
			Best = *It;
			BestHeight = FMath::Abs(AtPlayer.Altitude);
		}
	}
	AVH_GlobeReferenceActor* Chosen = Best ? Best : First;
	if (Chosen != LastGlobe.Get())
	{
		LastGlobe = Chosen;
		UE_LOG(LogCesiumIonVrtak, Log, TEXT("Aligning with %s. Globes:%s"), Chosen ? *Chosen->GetName() : TEXT("none"), *Candidates);
	}
	return Chosen;
}

bool ACesiumIonVrtakActor::SyncGeoreference()
{
	AVH_GlobeReferenceActor* Globe = FindGlobe();
	if (!Globe || !Georeference)
	{
		return false;
	}
	FGeodeticCoord3D Origin;
	if (AVH_GlobeReferenceActor_ENU* Enu = Cast<AVH_GlobeReferenceActor_ENU>(Globe))
	{
		Origin = Enu->GetLocation();
	}
	else
	{
		// An Earth-centred globe has no origin of its own: anchor Cesium on the ground
		// below the player, and move the anchor only after a move of about 5 km.
		APlayerController* Player = GetWorld()->GetFirstPlayerController();
		const APawn* Pawn = Player ? Player->GetPawn() : nullptr;
		Origin = Globe->WorldToGeodeticDouble(Pawn ? Pawn->GetActorLocation() : FVector::ZeroVector);
		Origin.Altitude = 0.0;
		if (bSynced && Globe == LastSyncedGlobe.Get() && FMath::Abs(Origin.Latitude - LastOrigin.Y) < 0.05
			&& FMath::Abs(Origin.Longitude - LastOrigin.X) < 0.05)
		{
			Origin = Geodetic(LastOrigin.X, LastOrigin.Y, LastOrigin.Z);
		}
	}
	if (FMath::IsNearlyEqual(Origin.Altitude, GroundSentinel, 1.0))
	{
		Origin.Altitude = 0.0;
	}
	const FVector OriginLLH(Origin.Longitude, Origin.Latitude, Origin.Altitude);
	const FTransform GlobeTransform = Globe->GetActorTransform();
	if (bSynced && OriginLLH.Equals(LastOrigin, 1e-9) && GlobeTransform.Equals(LastGlobeTransform, 1e-6))
	{
		return false;
	}
	const bool bGlobeChanged = Globe != LastSyncedGlobe.Get();
	bSynced = true;
	LastSyncedGlobe = Globe;
	if (bGlobeChanged)
	{
		// Entering the world: VR-TAK may switch its own map back on there.
		UpdateVrtakTerrain();
	}
	LastOrigin = OriginLLH;
	LastGlobeTransform = GlobeTransform;

	// Measure VR-TAK's east, north and up at the origin, in Unreal units per metre.
	const double CosLat = FMath::Max(1e-6, FMath::Cos(FMath::DegreesToRadians(Origin.Latitude)));
	const double DLat = MeasureMetres / MetresPerDegreeLatitude;
	const double DLon = MeasureMetres / (MetresPerDegreeLatitude * CosLat);
	const FVector P0 = Globe->GeodeticToWorldDouble(Origin);
	const FVector East = (Globe->GeodeticToWorldDouble(Geodetic(Origin.Longitude + DLon, Origin.Latitude, Origin.Altitude)) - P0) / MeasureMetres;
	const FVector North = (Globe->GeodeticToWorldDouble(Geodetic(Origin.Longitude, Origin.Latitude + DLat, Origin.Altitude)) - P0) / MeasureMetres;
	const FVector Up = (Globe->GeodeticToWorldDouble(Geodetic(Origin.Longitude, Origin.Latitude, Origin.Altitude + MeasureMetres)) - P0) / MeasureMetres;

	// Cesium's local frame at its origin: +X east, +Y south, +Z up, 100 units per metre.
	const FMatrix CesiumToVrtak(East / 100.0, -North / 100.0, Up / 100.0, P0);
	Georeference->SetOriginLongitudeLatitudeHeight(OriginLLH);
	Georeference->SetActorTransform(FTransform(CesiumToVrtak));
	UE_LOG(LogCesiumIonVrtak, Log,
		TEXT("Georeference aligned with VR-TAK's globe: origin %.6f, %.6f, %.1f m; %.1f units/m; yaw %.1f."),
		Origin.Latitude, Origin.Longitude, Origin.Altitude, East.Size(), FTransform(CesiumToVrtak).Rotator().Yaw);
	return true;
}

void ACesiumIonVrtakActor::Tick(float DeltaSeconds)
{
	Super::Tick(DeltaSeconds);
	ElapsedSeconds += DeltaSeconds;
	if (GrabPointer.IsValid() && PanelHolder && GetWorld())
	{
		APlayerController* Player = GetWorld()->GetFirstPlayerController();
		APlayerCameraManager* Camera = Player ? Player->PlayerCameraManager : nullptr;
		if (Camera && ElapsedSeconds - GrabStartedAt < 120.0)
		{
			const FVector Location = (GrabOffset * GrabPointer->GetComponentTransform()).GetLocation();
			FRotator Facing = (Location - Camera->GetCameraLocation()).Rotation();
			Facing.Pitch = 0.0;
			Facing.Roll = 0.0;
			PanelHolder->SetActorLocationAndRotation(Location, Facing);
		}
		else
		{
			EndGrab();
		}
	}
	if (bFollowPanel && PanelHolder && GetWorld())
	{
		// Glide after the view (a few cm behind the head's turns) instead of being nailed to it.
		APlayerController* Player = GetWorld()->GetFirstPlayerController();
		if (APlayerCameraManager* Camera = Player ? Player->PlayerCameraManager : nullptr)
		{
			const FVector CameraLocation = Camera->GetCameraLocation();
			const FRotator Heading(0.0, Camera->GetCameraRotation().Yaw + PanelYawOffset, 0.0);
			const FVector Target = CameraLocation + Heading.Vector() * 120.0;
			const FVector Location = FMath::VInterpTo(PanelHolder->GetActorLocation(), Target, DeltaSeconds, 2.5f);
			PanelHolder->SetActorLocationAndRotation(Location, (Location - CameraLocation).Rotation());
		}
	}
	if (!bRibbonButtonRegistered && RibbonTries < 12 && ElapsedSeconds >= NextRibbonTryAt)
	{
		// VR-TAK's ribbon is built after the world starts: try every 5 s, up to a minute.
		++RibbonTries;
		NextRibbonTryAt = ElapsedSeconds + 5.0;
		RegisterRibbonButton();
	}
	if (ElapsedSeconds >= NextXrLogAt)
	{
		// Headset diagnostics: whether an XR system exists, whether the view is stereoscopic
		// (the person is in VR), and whether the panel is a screen widget (which a headset does
		// not show). Written when it changes, and every minute.
		const bool bXrSystem = GEngine && GEngine->XRSystem.IsValid();
		const bool bStereo = GEngine && GEngine->IsStereoscopic3D();
		const bool bPanelOnScreen = Panel && Panel->IsInViewport();
		const FString XrState = FString::Printf(TEXT("XR system %s; stereoscopic 3D %s; panel is a screen widget: %s."),
			bXrSystem ? TEXT("present") : TEXT("none"), bStereo ? TEXT("ON (in VR)") : TEXT("off"),
			bPanelOnScreen ? TEXT("yes") : TEXT("no"));
		NextXrLogAt = ElapsedSeconds + 5.0;
		static double LastWrittenAt = -100.0;
		if (bStereo != bWasStereo)
		{
			bWasStereo = bStereo;
			MovePanelForView(bStereo);
		}
		if (XrState != LastXrState || ElapsedSeconds - LastWrittenAt >= 60.0)
		{
			LastXrState = XrState;
			LastWrittenAt = ElapsedSeconds;
			UE_LOG(LogCesiumIonVrtak, Log, TEXT("%s"), *XrState);
		}
	}
	if (Tilesets.Num() > 0 && ElapsedSeconds >= NextDiagnosticsAt && DiagnosticsLogged < 4)
	{
		NextDiagnosticsAt = ElapsedSeconds + 30.0;
		DiagnosticsLogged++;
		LogLayerDiagnostics();
	}
	if (ScreenshotAt >= 0.0 && ElapsedSeconds >= ScreenshotAt && ScreenshotsTaken < 3 && Session && Session->IsSigningIn())
	{
		// A person is signing in in the browser: take the pictures 60 s after they finish,
		// so the content they signed in for has loaded.
		ScreenshotAt = ElapsedSeconds + 60.0;
		if (!bScreenshotsWaitForSignIn)
		{
			bScreenshotsWaitForSignIn = true;
			UE_LOG(LogCesiumIonVrtak, Log, TEXT("Screenshots wait for the sign-in in the browser."));
		}
	}
	if (ScreenshotAt >= 0.0 && ElapsedSeconds >= ScreenshotAt && ScreenshotsTaken < 3)
	{
		ScreenshotsTaken++;
		ScreenshotAt += 30.0;
		const FString File = FPaths::ScreenShotDir() / FString::Printf(TEXT("CesiumIon-%d.png"), ScreenshotsTaken);
		// With the UI, so the Cesium ion panel is in the picture.
		FScreenshotRequest::RequestScreenshot(File, /*bShowUI*/ true, /*bAddFilenameSuffix*/ false);
		UE_LOG(LogCesiumIonVrtak, Log, TEXT("Screenshot requested: %s"), *File);
	}
	if (TestOrigin.Num() >= 2 && !bTeleportedInTOC && EnterWorldAt >= 0.0 && ElapsedSeconds >= FMath::Max(1.0, EnterWorldAt - 4.0))
	{
		// Still in the TOC: this moves VR-TAK's area of operations, so the world opens there.
		bTeleportedInTOC = TeleportPlayer(TestOrigin[0], TestOrigin[1], TestOrigin.Num() > 2 ? TestOrigin[2] : 0.0, 0.0, 0.0, false);
	}
	if (EnterWorldAt >= 0.0 && ElapsedSeconds >= EnterWorldAt)
	{
		EnterWorldAt = -1.0;
		if (APlayerController* Player = GetWorld()->GetFirstPlayerController())
		{
			UE_LOG(LogCesiumIonVrtak, Log, TEXT("Entering VR-TAK's world (ce GoToWorld)."));
			Player->ConsoleCommand(TEXT("ce GoToWorld"));
			EnteredWorldAt = ElapsedSeconds;
		}
	}
	if (EnteredWorldAt >= 0.0 && ElapsedSeconds >= EnteredWorldAt + 10.0)
	{
		// In the world: place the view over the Cesium content.
		EnteredWorldAt = -1.0;
		if (TestCamera.Num() >= 5)
		{
			TeleportPlayer(TestCamera[0], TestCamera[1], TestCamera[2], TestCamera[3], TestCamera[4], true);
		}
	}
	if (Panel && ShowSettingsAt >= 0.0 && ElapsedSeconds >= ShowSettingsAt)
	{
		ShowSettingsAt = -1.0;
		Panel->ShowSettings(true);
	}
	if (Panel && HideSettingsAt >= 0.0 && ElapsedSeconds >= HideSettingsAt && ShowSettingsAt < 0.0)
	{
		HideSettingsAt = -1.0;
		Panel->ShowSettings(false);
	}
	if (bStartSignIn && Panel && ElapsedSeconds >= 8.0)
	{
		bStartSignIn = false;
		if (Session->NeedsToken() && !Session->HasToken() && !Session->IsSigningIn())
		{
			SignInOrOut();
		}
	}
	if (QuitAt >= 0.0 && ElapsedSeconds >= QuitAt)
	{
		QuitAt = -1.0;
		UE_LOG(LogCesiumIonVrtak, Log, TEXT("Quitting after the test run."));
		FPlatformMisc::RequestExit(false);
	}
	SyncCountdown -= DeltaSeconds;
	if (SyncCountdown <= 0.f)
	{
		SyncCountdown = 1.f;
		EnsurePanel();
		if (Panel && (ElapsedSeconds >= 10.0 && !bPanelDiagnosed))
		{
			// One-time diagnostics: why the panel may not be visible.
			bPanelDiagnosed = true;
			FVector2D ViewportSize(0, 0);
			if (GEngine && GEngine->GameViewport)
			{
				GEngine->GameViewport->GetViewportSize(ViewportSize);
			}
			const FGeometry Geometry = Panel->GetCachedGeometry();
			UE_LOG(LogCesiumIonVrtak, Log,
				TEXT("Panel: in viewport %d, visible %d, tree %d, root %s, desired %s, size %s at %s; viewport %s."),
				Panel->IsInViewport(), Panel->IsVisible(), Panel->WidgetTree != nullptr,
				Panel->WidgetTree && Panel->WidgetTree->RootWidget ? *Panel->WidgetTree->RootWidget->GetClass()->GetName() : TEXT("none"),
				*Panel->GetDesiredSize().ToString(), *Geometry.GetLocalSize().ToString(),
				*Geometry.GetAbsolutePosition().ToString(), *ViewportSize.ToString());
		}
		if (bAnswerTutorialPrompt && !bTutorialDismissed && ElapsedSeconds >= 5.0 && ElapsedSeconds < 300.0)
		{
			bTutorialDismissed = DismissTutorialPrompt();
		}
		SyncGeoreference();
	}
}

void ACesiumIonVrtakActor::OpenPanel()
{
	APlayerController* Player = GetWorld()->GetFirstPlayerController();
	if (!Player)
	{
		// No local player yet: EnsurePanel tries again every second.
		return;
	}
	Panel = CreateWidget<UCesiumIonVrtakPanel>(Player);
	if (!Panel)
	{
		UE_LOG(LogCesiumIonVrtak, Warning, TEXT("Could not create the Cesium ion panel."));
		return;
	}
	Panel->SetOwnerActor(this);
	bPanelInWorld = false;
	// A new widget has no placement: without this, PlacePanel sees an unchanged size and skips it,
	// and the panel fills the screen.
	PanelSize = FVector2D::ZeroVector;
	// Desktop view: docked on the right. (In VR, OpenPanelInWorld uses VR-TAK's UI subsystem.)
	Panel->AddToViewport(50);
	PlacePanel();
	Panel->Refresh();
	UE_LOG(LogCesiumIonVrtak, Log, TEXT("Cesium ion panel on screen (top right, below the ribbon)."));
}

UObject* ACesiumIonVrtakActor::FindWorldSubsystem(const TCHAR* ClassPath) const
{
	UClass* Class = FindObject<UClass>(nullptr, ClassPath);
	UWorld* World = GetWorld();
	if (!Class || !World || !Class->IsChildOf(UWorldSubsystem::StaticClass()))
	{
		return nullptr;
	}
	return World->GetSubsystemBase(TSubclassOf<UWorldSubsystem>(Class));
}

UObject* ACesiumIonVrtakActor::FindGameInstanceSubsystem(const TCHAR* ClassPath) const
{
	UClass* Class = FindObject<UClass>(nullptr, ClassPath);
	UGameInstance* Game = GetGameInstance();
	if (!Class || !Game || !Class->IsChildOf(UGameInstanceSubsystem::StaticClass()))
	{
		return nullptr;
	}
	return Game->GetSubsystemBase(TSubclassOf<UGameInstanceSubsystem>(Class));
}

void ACesiumIonVrtakActor::RegisterRibbonButton()
{
	UClass* ButtonBase = FindObject<UClass>(nullptr, TEXT("/Script/Game.Game_UserWidget_RibbonButton"));
	UObject* Ribbon = FindWorldSubsystem(TEXT("/Script/Game.Game_Subsystem_Ribbon"));
	APlayerController* Player = GetWorld() ? GetWorld()->GetFirstPlayerController() : nullptr;
	if (!ButtonBase || !Ribbon || !Player)
	{
		return; // not up yet: tried again
	}
	// VR-TAK's ribbon buttons are Blueprint widgets derived from the native button class (the
	// native class draws nothing): use one that VR-TAK has loaded for its own buttons.
	UClass* ButtonClass = nullptr;
	for (TObjectIterator<UClass> It; It; ++It)
	{
		const FString Name = It->GetName();
		if (*It != ButtonBase && It->IsChildOf(ButtonBase) && Name.EndsWith(TEXT("_C"))
			&& !It->HasAnyClassFlags(CLASS_Abstract | CLASS_Deprecated | CLASS_NewerVersionExists)
			&& !Name.StartsWith(TEXT("SKEL_")) && !Name.StartsWith(TEXT("REINST_")))
		{
			ButtonClass = *It;
			if (Name.Contains(TEXT("Ribbon")) && !Name.Contains(TEXT("Tool")) && !Name.Contains(TEXT("Dropdown")))
			{
				break; // a plain ribbon button
			}
		}
	}
	if (!ButtonClass)
	{
		if (RibbonTries == 12)
		{
			UE_LOG(LogCesiumIonVrtak, Warning, TEXT("No ribbon button widget class is loaded in VR-TAK; no Cesium ion ribbon button."));
		}
		return;
	}
	UUserWidget* Button = CreateWidget<UUserWidget>(Player, ButtonClass);
	if (!Button)
	{
		return;
	}
	SetStringProperty(Button, TEXT("ID"), TEXT("CesiumIon"));
	SetStringProperty(Button, TEXT("Text"), TEXT("Cesium ion"));
	SetStringProperty(Button, TEXT("RibbonToolTip"), TEXT("Add Cesium ion content: Google Photorealistic 3D Tiles, terrain, imagery and your own 3D Tiles"));
	SetStringProperty(Button, TEXT("DefaultTab"), TEXT("Home"));
	SetStringProperty(Button, TEXT("DefaultCategory"), TEXT("Application"));
	if (FFloatProperty* Index = FindFProperty<FFloatProperty>(ButtonClass, TEXT("DefaultIndexInCategory")))
	{
		Index->SetPropertyValue_InContainer(Button, 100.0f);
	}
	if (FMulticastDelegateProperty* Clicked = FindFProperty<FMulticastDelegateProperty>(ButtonBase, TEXT("OnClicked")))
	{
		FScriptDelegate Handler;
		Handler.BindUFunction(this, GET_FUNCTION_NAME_CHECKED(ACesiumIonVrtakActor, HandleRibbonButtonClicked));
		Clicked->AddDelegate(Handler, Button);
	}
	CallWithObject(Ribbon, TEXT("RegisterButton"), Button);
	RibbonButton = Button;
	bRibbonButtonRegistered = true;
	UE_LOG(LogCesiumIonVrtak, Log, TEXT("Registered the Cesium ion ribbon button (Home > Application) with widget class %s."), *ButtonClass->GetName());
}

void ACesiumIonVrtakActor::HidePanel()
{
	if (bPanelInWorld)
	{
		ClosePanelInWorld();
	}
	else if (Panel)
	{
		Panel->RemoveFromParent();
		Panel = nullptr;
	}
	bPanelWanted = false;
}

void ACesiumIonVrtakActor::HandleRibbonButtonClicked(UUserWidget* Button)
{
	const bool bVr = GEngine && GEngine->IsStereoscopic3D();
	UE_LOG(LogCesiumIonVrtak, Log, TEXT("Ribbon button clicked (VR: %s)."), bVr ? TEXT("yes") : TEXT("no"));
	const bool bShown = Panel && (bPanelInWorld || Panel->IsInViewport());
	if (bShown && !(bVr && !bPanelInWorld))
	{
		// Open: close it (and do not bring it back by itself).
		HidePanel();
		return;
	}
	if (bShown)
	{
		// A screen widget while in VR: open it in the world instead.
		Panel->RemoveFromParent();
		Panel = nullptr;
	}
	bPanelWanted = true;
	if (!(bVr && OpenPanelInWorld()))
	{
		OpenPanel();
	}
}

bool ACesiumIonVrtakActor::OpenPanelInWorld()
{
	UObject* Core = FindGameInstanceSubsystem(TEXT("/Script/VH_UI.UI_Subsystem_Core"));
	if (!Core)
	{
		UE_LOG(LogCesiumIonVrtak, Warning, TEXT("VR-TAK's UI subsystem was not found; using the plugin's own floating panel."));
		return OpenPanelAsWidgetComponent();
	}
	// The panel is created by VR-TAK from its class, so it is given its owner here.
	// A floating panel: 1.2 m ahead of the camera (cm; +X is forward), 560 x 820 px.
	UCesiumIonVrtakPanel* Opened = Cast<UCesiumIonVrtakPanel>(OpenInWorldWithSettings(
		Core, UCesiumIonVrtakPanel::StaticClass(), FindWorldUiActorClass(Core), FVector2D(560.0, 820.0), FVector(120.0, 0.0, 0.0)));
	if (!Opened)
	{
		UE_LOG(LogCesiumIonVrtak, Warning, TEXT("VR-TAK's UI subsystem did not open the panel; using the plugin's own floating panel."));
		return OpenPanelAsWidgetComponent();
	}
	Opened->SetOwnerActor(this);
	Opened->Refresh();
	Panel = Opened;
	bPanelInWorld = true;
	// The actor VR-TAK put the panel on (for docking and resizing); it stays VR-TAK's to destroy.
	PanelHolder = Cast<AActor>(CallWithObject(Core, TEXT("GetUserWidgetWorldUIActor"), Opened));
	bOwnPanelHolder = false;
	PanelYawOffset = 0.0;
	PanelBaseScale = PanelHolder ? PanelHolder->GetActorScale3D().X : 1.0;
	Opened->Refresh();
	UE_LOG(LogCesiumIonVrtak, Log, TEXT("Cesium ion panel opened through VR-TAK's UI subsystem (world-space in VR), world UI actor %s."),
		PanelHolder ? *PanelHolder->GetName() : TEXT("none"));
	return true;
}

bool ACesiumIonVrtakActor::OpenPanelAsWidgetComponent()
{
	APlayerController* Player = GetWorld() ? GetWorld()->GetFirstPlayerController() : nullptr;
	APlayerCameraManager* Camera = Player ? Player->PlayerCameraManager : nullptr;
	if (!Camera)
	{
		return false;
	}
	UCesiumIonVrtakPanel* NewPanel = CreateWidget<UCesiumIonVrtakPanel>(Player);
	if (!NewPanel)
	{
		return false;
	}
	NewPanel->SetOwnerActor(this);
	// 120 cm ahead of the camera, facing it (a widget component shows its face toward -X).
	const FVector CameraLocation = Camera->GetCameraLocation();
	const FVector Forward = Camera->GetCameraRotation().Vector();
	const FVector Location = CameraLocation + Forward * 120.0;
	const FRotator Rotation = (Location - CameraLocation).Rotation();
	FActorSpawnParameters Params;
	Params.ObjectFlags |= RF_Transient;
	AActor* Holder = GetWorld()->SpawnActor<AActor>(AActor::StaticClass(), Location, Rotation, Params);
	if (!Holder)
	{
		return false;
	}
	UWidgetComponent* Component = NewObject<UWidgetComponent>(Holder, TEXT("CesiumIonPanel"));
	Holder->SetRootComponent(Component);
	Component->RegisterComponent();
	Component->SetWidgetSpace(EWidgetSpace::World);
	Component->SetDrawSize(FVector2D(560.0, 820.0));
	Component->SetTwoSided(true);
	Component->SetWidget(NewPanel);
	// 560 x 820 px is 5.6 x 8.2 m at 1 px per cm: scale it to about 0.56 x 0.82 m.
	Component->SetRelativeScale3D(FVector(0.1, 0.1, 0.1));
	Holder->SetActorLocationAndRotation(Location, Rotation);
	PanelHolder = Holder;
	bOwnPanelHolder = true;
	Panel = NewPanel;
	bPanelInWorld = true;
	PanelYawOffset = 0.0;
	PanelBaseScale = Holder->GetActorScale3D().X;
	NewPanel->Refresh();
	UE_LOG(LogCesiumIonVrtak, Log, TEXT("Cesium ion panel opened as the plugin's own floating panel, 120 cm ahead."));
	return true;
}

void ACesiumIonVrtakActor::DockPanel(double YawDegrees)
{
	APlayerController* Player = GetWorld() ? GetWorld()->GetFirstPlayerController() : nullptr;
	APlayerCameraManager* Camera = Player ? Player->PlayerCameraManager : nullptr;
	if (!PanelHolder || !Camera)
	{
		return;
	}
	PanelYawOffset = YawDegrees;
	// Level with the eyes, 120 cm out, YawDegrees to the side of the heading.
	const FVector CameraLocation = Camera->GetCameraLocation();
	const FRotator Heading(0.0, Camera->GetCameraRotation().Yaw + YawDegrees, 0.0);
	const FVector Location = CameraLocation + Heading.Vector() * 120.0;
	PanelHolder->SetActorLocationAndRotation(Location, (Location - CameraLocation).Rotation());
}

void ACesiumIonVrtakActor::ToggleFollowPanel()
{
	bFollowPanel = !bFollowPanel;
}

void ACesiumIonVrtakActor::ScalePanel(float Factor)
{
	if (!PanelHolder)
	{
		return;
	}
	// Measured against the size the panel opened at (VR-TAK's own holder has its own scale).
	const double Current = PanelHolder->GetActorScale3D().X;
	const double Wanted = FMath::Clamp(Current * Factor, PanelBaseScale * 0.5, PanelBaseScale * 2.5);
	PanelHolder->SetActorScale3D(FVector(Wanted));
	UE_LOG(LogCesiumIonVrtak, Log, TEXT("Panel size x%.2f of its opening size."), Wanted / FMath::Max(PanelBaseScale, 1e-6));
}

void ACesiumIonVrtakActor::StartGrab()
{
	APlayerController* Player = GetWorld() ? GetWorld()->GetFirstPlayerController() : nullptr;
	APlayerCameraManager* Camera = Player ? Player->PlayerCameraManager : nullptr;
	if (!PanelHolder || !Camera)
	{
		return;
	}
	// The controller whose pointer is on the panel carries it, as if held at the end of a stick.
	UWidgetInteractionComponent* Best = nullptr;
	double BestDistance = TNumericLimits<double>::Max();
	for (TObjectIterator<UWidgetInteractionComponent> It; It; ++It)
	{
		if (It->GetWorld() != GetWorld() || !It->IsActive())
		{
			continue;
		}
		const UWidgetComponent* Hovered = It->GetHoveredWidgetComponent();
		const bool bOnPanel = Hovered && Hovered->GetOwner() == PanelHolder;
		const double Distance = bOnPanel ? 0.0 : FVector::Dist(It->GetComponentLocation(), PanelHolder->GetActorLocation());
		if (Distance < BestDistance)
		{
			BestDistance = Distance;
			Best = *It;
		}
	}
	GrabStartedAt = ElapsedSeconds;
	if (Best)
	{
		GrabPointer = Best;
		GrabOffset = PanelHolder->GetActorTransform().GetRelativeTransform(Best->GetComponentTransform());
		bGrabbingWithHead = false;
		UE_LOG(LogCesiumIonVrtak, Log, TEXT("Panel grabbed with %s."), *Best->GetOwner()->GetName());
	}
	else
	{
		// No controller pointer found: carry it with the head (turn to move it).
		bGrabbingWithHead = true;
		bFollowPanel = true;
		UE_LOG(LogCesiumIonVrtak, Log, TEXT("Panel grabbed with the head (no controller pointer found)."));
	}
}

void ACesiumIonVrtakActor::EndGrab()
{
	if (bGrabbingWithHead)
	{
		bFollowPanel = false;
		bGrabbingWithHead = false;
	}
	if (GrabPointer.IsValid())
	{
		UE_LOG(LogCesiumIonVrtak, Log, TEXT("Panel placed."));
	}
	GrabPointer.Reset();
}

void ACesiumIonVrtakActor::ClosePanelInWorld()
{
	bFollowPanel = false;
	if (PanelHolder && bOwnPanelHolder)
	{
		PanelHolder->Destroy();
	}
	else if (Panel)
	{
		CallWithObject(FindGameInstanceSubsystem(TEXT("/Script/VH_UI.UI_Subsystem_Core")), TEXT("CloseUserWidget"), Panel);
	}
	PanelHolder = nullptr;
	Panel = nullptr;
	bPanelInWorld = false;
}

void ACesiumIonVrtakActor::MovePanelForView(bool bVr)
{
	if (!bPanelWanted || !Panel)
	{
		return;
	}
	if (bVr && !bPanelInWorld)
	{
		Panel->RemoveFromParent();
		Panel = nullptr;
		if (!OpenPanelInWorld())
		{
			OpenPanel();
		}
	}
	else if (!bVr && bPanelInWorld)
	{
		ClosePanelInWorld();
		OpenPanel();
	}
}

void ACesiumIonVrtakActor::PlacePanel()
{
	// VR-TAK's viewport lays widgets out from the top left whatever their anchors, so
	// the top-right corner is computed: viewport width in UI units (pixels / DPI scale).
	const FVector2D ViewportPixels = UWidgetLayoutLibrary::GetViewportSize(this);
	const float Scale = FMath::Max(0.1f, UWidgetLayoutLibrary::GetViewportScale(this));
	// VR-TAK's ribbon is about 130 px high (in pixels, whatever the UI scale).
	const float Top = 140.f / Scale;
	const FVector2D Size(540.f, FMath::Min(900.f, ViewportPixels.Y / Scale - Top - 16.f / Scale));
	const FVector2D Position(FMath::Max(0.f, ViewportPixels.X / Scale - Size.X - 16.f / Scale), Top);
	if (Position.Equals(PanelPosition) && Size.Equals(PanelSize))
	{
		return;
	}
	PanelPosition = Position;
	PanelSize = Size;
	Panel->SetAnchorsInViewport(FAnchors(0.f, 0.f));
	Panel->SetAlignmentInViewport(FVector2D(0.f, 0.f));
	Panel->SetDesiredSizeInViewport(Size);
	Panel->SetPositionInViewport(Position, /*bRemoveDPIScale*/ false);
}

bool ACesiumIonVrtakActor::DismissTutorialPrompt()
{
	for (TObjectIterator<UTextBlock> It; It; ++It)
	{
		if (It->GetWorld() != GetWorld() || !It->GetText().ToString().Contains(TEXT("start the VR-TAK tutorial")))
		{
			continue;
		}
		UUserWidget* Prompt = It->GetTypedOuter<UUserWidget>();
		if (!Prompt || !Prompt->WidgetTree)
		{
			continue;
		}
		UButton* No = nullptr;
		Prompt->WidgetTree->ForEachWidget([&No](UWidget* Widget)
		{
			UTextBlock* Label = Cast<UTextBlock>(Widget);
			if (!No && Label && Label->GetText().ToString().TrimStartAndEnd() == TEXT("No"))
			{
				for (UPanelWidget* Parent = Label->GetParent(); Parent; Parent = Parent->GetParent())
				{
					if (UButton* Button = Cast<UButton>(Parent))
					{
						No = Button;
						break;
					}
				}
			}
		});
		if (No)
		{
			UE_LOG(LogCesiumIonVrtak, Log, TEXT("Answered VR-TAK's first-start tutorial prompt with No (test run)."));
			No->OnClicked.Broadcast();
			return true;
		}
	}
	return false;
}

void ACesiumIonVrtakActor::Message(const FString& Text)
{
	UE_LOG(LogCesiumIonVrtak, Log, TEXT("%s"), *Text);
	if (Panel)
	{
		Panel->ShowMessage(Text);
	}
}

void ACesiumIonVrtakActor::RefreshAssets()
{
	if (!Session->IsReady())
	{
		if (Panel)
		{
			Panel->Refresh();
		}
		return;
	}
	TWeakObjectPtr<ACesiumIonVrtakActor> Self(this);
	Session->ListAssets(FCesiumIonVrtakAssetsResult::CreateLambda([Self](bool bOk, const FString& Text)
	{
		if (!Self.IsValid())
		{
			return;
		}
		Self->Message(Text);
		for (int64 Id : Self->PendingTestLoads)
		{
			const FCesiumIonVrtakAsset* Asset = Self->Session->GetAssets().FindByPredicate(
				[Id](const FCesiumIonVrtakAsset& Each) { return Each.Id == Id; });
			FCesiumIonVrtakAsset Load;
			Load.Id = Id;
			const FString Featured = UCesiumIonVrtakPanel::FeaturedName(Id);
			Load.Name = Asset ? Asset->Name : !Featured.IsEmpty() ? Featured : FString::Printf(TEXT("Asset %lld"), Id);
			Load.Type = Asset ? Asset->Type : UCesiumIonVrtakPanel::FeaturedType(Id);
			if (!Self->IsLoaded(Id))
			{
				Self->ToggleAsset(Load);
			}
		}
		Self->PendingTestLoads.Reset();
		if (Self->Panel)
		{
			Self->Panel->Refresh();
		}
		// Who is signed in (the status pill's tooltip); once per server and token.
		Self->Session->FetchAccount(FSimpleDelegate::CreateLambda([Self]()
		{
			if (Self.IsValid() && Self->Panel)
			{
				Self->Panel->Refresh();
			}
		}));
	}));
}

void ACesiumIonVrtakActor::SaveServerSettings(bool bSelfHosted, const FString& ServerUrl, const FString& ApiUrl, bool bSingleUser)
{
	Session->SaveSettings(bSelfHosted, ServerUrl, ApiUrl, bSingleUser);
	// 3D Tiles added from now on stream from the new server (those already in the world stay).
	SelfHostedServer = nullptr;
	if (Session->IsSelfHosted())
	{
		SelfHostedServer = NewObject<UCesiumIonServer>(this);
		SelfHostedServer->DisplayName = Session->Describe();
		SelfHostedServer->ServerUrl = Session->GetServerUrl();
		SelfHostedServer->ApiUrl = Session->GetApiUrl();
	}
	Message(TEXT("Saved. Server: ") + Session->Describe() + TEXT("."));
	if (Panel)
	{
		Panel->ShowSettings(Session->NeedsToken() && !Session->HasToken());
	}
	RefreshAssets();
}

void ACesiumIonVrtakActor::SignInOrOut()
{
	if (Session->IsSigningIn())
	{
		Session->CancelSignIn();
		return;
	}
	if (Session->HasToken())
	{
		Session->SignOut();
		Message(TEXT("Signed out; the saved access was removed from this PC."));
		if (Panel)
		{
			Panel->Refresh();
		}
		return;
	}
	TWeakObjectPtr<ACesiumIonVrtakActor> Self(this);
	Message(TEXT("Sign in to Cesium ion in the browser, then come back here."));
	Session->SignIn(FCesiumIonVrtakSignInResult::CreateLambda([Self](bool bOk, const FString& Text)
	{
		if (Self.IsValid())
		{
			Self->Message(Text);
			Self->RefreshAssets();
		}
	}));
	if (Panel)
	{
		Panel->Refresh();
	}
}

bool ACesiumIonVrtakActor::IsLoaded(int64 AssetId) const
{
	return Tilesets.Contains(AssetId) || Overlays.Contains(AssetId);
}

TArray<TPair<int64, FString>> ACesiumIonVrtakActor::GetLoaded() const
{
	TArray<TPair<int64, FString>> Out;
	for (const TPair<int64, FString>& Entry : LoadedNames)
	{
		Out.Add(Entry);
	}
	return Out;
}

FString ACesiumIonVrtakActor::GetLoadedType(int64 AssetId) const
{
	const FString* Type = LoadedTypes.Find(AssetId);
	return Type ? *Type : FString(TEXT("3DTILES"));
}

bool ACesiumIonVrtakActor::IsSupportedType(const FString& Type)
{
	return Type == TEXT("3DTILES") || Type == TEXT("TERRAIN") || Type == TEXT("IMAGERY");
}

void ACesiumIonVrtakActor::ToggleAsset(const FCesiumIonVrtakAsset& Asset)
{
	if (ACesium3DTileset** Existing = Tilesets.Find(Asset.Id))
	{
		if (IsValid(*Existing))
		{
			// Imagery over this terrain goes with it.
			(*Existing)->Destroy();
		}
		Tilesets.Remove(Asset.Id);
		LoadedNames.Remove(Asset.Id);
		LoadedTypes.Remove(Asset.Id);
		ForgetOrphanedImagery();
		UpdateVrtakTerrain();
		Message(TEXT("Removed ") + Asset.Name + TEXT("."));
	}
	else if (TWeakObjectPtr<UCesiumIonRasterOverlay>* Overlay = Overlays.Find(Asset.Id))
	{
		ACesium3DTileset* OverlayOwner = Overlay->IsValid() ? Cast<ACesium3DTileset>((*Overlay)->GetOwner()) : nullptr;
		if (Overlay->IsValid())
		{
			(*Overlay)->DestroyComponent();
		}
		if (IsValid(OverlayOwner))
		{
			OverlayOwner->RefreshTileset();
		}
		Overlays.Remove(Asset.Id);
		LoadedNames.Remove(Asset.Id);
		LoadedTypes.Remove(Asset.Id);
		Message(TEXT("Removed ") + Asset.Name + TEXT("."));
	}
	else if (Asset.Type == TEXT("3DTILES") || Asset.Type == TEXT("TERRAIN"))
	{
		AddTileset(Asset.Id, Asset.Name, Asset.Type);
	}
	else if (Asset.Type == TEXT("IMAGERY"))
	{
		AddImagery(Asset.Id, Asset.Name);
	}
	else
	{
		Message(Asset.Name + TEXT(": this asset type is not supported in VR-TAK yet."));
	}
	if (Panel)
	{
		Panel->Refresh();
	}
}

void ACesiumIonVrtakActor::OnTilesetLoadFailure(const FCesium3DTilesetLoadFailureDetails& Details)
{
	const ACesium3DTileset* Tileset = Details.Tileset.Get();
	for (const TPair<int64, ACesium3DTileset*>& Entry : Tilesets)
	{
		if (Entry.Value == Tileset)
		{
			UE_LOG(LogCesiumIonVrtak, Warning, TEXT("Layer #%lld failed to load: HTTP %d, %s"),
				Entry.Key, Details.HttpStatusCode, *Details.Message);
		}
	}
}

void ACesiumIonVrtakActor::OnOverlayLoadFailure(const FCesiumRasterOverlayLoadFailureDetails& Details)
{
	const UCesiumRasterOverlay* Overlay = Details.Overlay.Get();
	for (const TPair<int64, TWeakObjectPtr<UCesiumIonRasterOverlay>>& Entry : Overlays)
	{
		if (Entry.Value.Get() == Overlay)
		{
			UE_LOG(LogCesiumIonVrtak, Warning, TEXT("Imagery #%lld failed to load: HTTP %d, %s"),
				Entry.Key, Details.HttpStatusCode, *Details.Message);
		}
	}
}

void ACesiumIonVrtakActor::LogLayerDiagnostics()
{
	FVector Camera = FVector::ZeroVector;
	if (APlayerController* Player = GetWorld()->GetFirstPlayerController())
	{
		if (Player->PlayerCameraManager)
		{
			Camera = Player->PlayerCameraManager->GetCameraLocation();
		}
	}
	UE_LOG(LogCesiumIonVrtak, Log, TEXT("Diagnostics: camera at %s; georeference at %s, scale %s."), *Camera.ToString(),
		Georeference ? *Georeference->GetActorLocation().ToString() : TEXT("(none)"),
		Georeference ? *Georeference->GetActorScale3D().ToString() : TEXT("(none)"));
	for (const TPair<int64, ACesium3DTileset*>& Entry : Tilesets)
	{
		const ACesium3DTileset* Tileset = Entry.Value;
		if (!IsValid(Tileset))
		{
			continue;
		}
		// Loaded tiles are the tileset's child components; their box is where the content is.
		const FBox Box = Tileset->GetComponentsBoundingBox(/*bNonColliding*/ true, /*bIncludeFromChildActors*/ true);
		UE_LOG(LogCesiumIonVrtak, Log, TEXT("Diagnostics: #%lld loaded %.0f%%, %d components, hidden %d, content %s (size %s), %.0f units from the camera."),
			Entry.Key, Tileset->GetLoadProgress(), Tileset->GetComponents().Num(), Tileset->IsHidden() ? 1 : 0,
			Box.IsValid ? *Box.GetCenter().ToString() : TEXT("(none)"), Box.IsValid ? *Box.GetSize().ToString() : TEXT("(none)"),
			Box.IsValid ? FVector::Dist(Box.GetCenter(), Camera) : -1.0);
	}
}

void ACesiumIonVrtakActor::UpdateVrtakTerrain(bool bShuttingDown)
{
	bool bCesiumTerrain = false;
	if (!bShuttingDown)
	{
		for (const TPair<int64, FString>& Entry : LoadedTypes)
		{
			bCesiumTerrain |= Entry.Value == TEXT("TERRAIN") && IsValid(Tilesets.FindRef(Entry.Key));
		}
	}
	if (!bCesiumTerrain && !bTurnedOffVrtakTerrain)
	{
		return;
	}
	if (!TileManagerCache.IsValid())
	{
		TileManagerCache = FindTileManager();
	}
	UObject* TileManager = TileManagerCache.Get();
	bool bEnabled = false;
	if (!TileManager || !IsTileManagerEnabled(TileManager, bEnabled))
	{
		UE_LOG(LogCesiumIonVrtak, Warning, TEXT("VR-TAK's tile manager was not found; its map stays as it is."));
		return;
	}
	if (bCesiumTerrain && bEnabled && SetTileManagerEnabled(TileManager, false))
	{
		bTurnedOffVrtakTerrain = true;
		Message(TEXT("VR-TAK's own terrain is off while Cesium terrain is in the world."));
	}
	else if (!bCesiumTerrain && bTurnedOffVrtakTerrain)
	{
		if (!bEnabled)
		{
			SetTileManagerEnabled(TileManager, true);
		}
		bTurnedOffVrtakTerrain = false;
		Message(TEXT("VR-TAK's own terrain is back on."));
	}
}

void ACesiumIonVrtakActor::ForgetOrphanedImagery()
{
	TArray<int64> Gone;
	for (const TPair<int64, TWeakObjectPtr<UCesiumIonRasterOverlay>>& Entry : Overlays)
	{
		const UCesiumIonRasterOverlay* Overlay = Entry.Value.Get();
		if (!Overlay || !IsValid(Overlay->GetOwner()) || Overlay->GetOwner()->IsActorBeingDestroyed())
		{
			Gone.Add(Entry.Key);
		}
	}
	for (int64 Id : Gone)
	{
		Overlays.Remove(Id);
		LoadedNames.Remove(Id);
		LoadedTypes.Remove(Id);
	}
}

void ACesiumIonVrtakActor::GoToAsset(int64 AssetId)
{
	ACesium3DTileset* Tileset = Tilesets.FindRef(AssetId);
	if (!IsValid(Tileset) || !IsValid(Georeference))
	{
		return;
	}
	// The tileset's own bounding volume (known as soon as tileset.json has loaded), not the loaded
	// meshes: a far-away tileset has loaded none, because nothing is in view.
	FVector Where = FVector::ZeroVector;
	double RadiusMetres = 0.0;
	bool bHaveBounds = false;
	if (const Cesium3DTilesSelection::Tileset* Native = Tileset->GetTileset())
	{
		if (const Cesium3DTilesSelection::Tile* Root = Native->getRootTile())
		{
			const CesiumGeometry::OrientedBoundingBox Box =
				Cesium3DTilesSelection::getOrientedBoundingBoxFromBoundingVolume(Root->getBoundingVolume());
			const glm::dvec3 Center = Box.getCenter();
			const glm::dmat3& Axes = Box.getHalfAxes();
			Where = Georeference->TransformUnrealPositionToLongitudeLatitudeHeight(
				Georeference->TransformEarthCenteredEarthFixedPositionToUnreal(FVector(Center.x, Center.y, Center.z)));
			RadiusMetres = glm::length(Axes[0] + Axes[1] + Axes[2]);
			bHaveBounds = RadiusMetres > 0.0 && FMath::IsFinite(Where.X) && FMath::IsFinite(Where.Y);
		}
	}
	if (!bHaveBounds)
	{
		UE_LOG(LogCesiumIonVrtak, Log, TEXT("View: no bounding volume for asset %lld yet (tileset %s)."), AssetId,
			Tileset->GetTileset() ? TEXT("created, no root tile") : TEXT("not created"));
		Message(TEXT("Still loading: try again in a moment."));
		return;
	}
	UE_LOG(LogCesiumIonVrtak, Log, TEXT("View: asset %lld centre %.5f, %.5f, %.0f m; radius %.0f m."), AssetId, Where.Y, Where.X, Where.Z, RadiusMetres);
	const double MetresPerDegree = 111320.0;
	if (RadiusMetres > 30000.0)
	{
		Message(LoadedNames.FindRef(AssetId) + TEXT(" covers too large an area to frame; use VR-TAK's Go To."));
		return;
	}
	// View it from the south, above and well back, looking north and down: never at its own position.
	const double Distance = FMath::Clamp(RadiusMetres * 2.2, 50.0, 3000.0);
	const double ViewLatitude = Where.Y - (Distance * 0.9) / MetresPerDegree;
	const double ViewHeight = Where.Z + Distance * 0.7;
	TeleportPlayer(ViewLatitude, Where.X, ViewHeight, 0.0, -35.0, true);
	Message(TEXT("Moved to view ") + LoadedNames.FindRef(AssetId) + TEXT("."));
}

UCesiumIonRasterOverlay* ACesiumIonVrtakActor::AddImagery(int64 AssetId, const FString& Name)
{
	auto FindTerrain = [this]() -> ACesium3DTileset*
	{
		ACesium3DTileset* Found = nullptr;
		for (const TPair<int64, FString>& Entry : LoadedTypes)
		{
			ACesium3DTileset* Candidate = Entry.Value == TEXT("TERRAIN") ? Tilesets.FindRef(Entry.Key) : nullptr;
			if (IsValid(Candidate))
			{
				Found = Candidate;
			}
		}
		return Found;
	};
	ACesium3DTileset* Terrain = FindTerrain();
	if (!Terrain && !Session->IsSelfHosted())
	{
		// Imagery drapes over Cesium terrain: add Cesium World Terrain for it.
		AddTileset(1, TEXT("Cesium World Terrain"), TEXT("TERRAIN"));
		Terrain = FindTerrain();
	}
	if (!Terrain)
	{
		Message(Name + TEXT(": add a terrain asset first. VR-TAK drapes Cesium ion imagery over Cesium terrain."));
		return nullptr;
	}
	// Cesium's default material has three imagery layers: Overlay0, 1 and 2.
	TSet<FString> Used;
	for (const TPair<int64, TWeakObjectPtr<UCesiumIonRasterOverlay>>& Entry : Overlays)
	{
		if (Entry.Value.IsValid() && Entry.Value->GetOwner() == Terrain)
		{
			Used.Add(Entry.Value->MaterialLayerKey);
		}
	}
	FString Key;
	for (int32 Layer = 0; Layer < 3 && Key.IsEmpty(); ++Layer)
	{
		const FString Candidate = FString::Printf(TEXT("Overlay%d"), Layer);
		if (!Used.Contains(Candidate))
		{
			Key = Candidate;
		}
	}
	if (Key.IsEmpty())
	{
		Message(Name + TEXT(": the terrain already shows three imagery layers. Remove one first."));
		return nullptr;
	}
	UCesiumIonRasterOverlay* Overlay = NewObject<UCesiumIonRasterOverlay>(Terrain, NAME_None, RF_Transient);
	// Activate once its properties are set (Cesium's advice for runtime overlays).
	Overlay->SetAutoActivate(false);
	Overlay->MaterialLayerKey = Key;
	Overlay->IonAssetID = AssetId;
	Overlay->IonAccessToken = Session->GetToken();
	if (SelfHostedServer)
	{
		Overlay->CesiumIonServer = SelfHostedServer;
	}
	Terrain->AddInstanceComponent(Overlay);
	Overlay->RegisterComponent();
	Overlay->Activate(true);
	// Tiles that loaded before the overlay have no texture coordinates for it; Cesium asserts when
	// it attaches the imagery to them (crash). Rebuild the tiles so they are made with the overlay.
	Terrain->RefreshTileset();
	Overlays.Add(AssetId, Overlay);
	LoadedNames.Add(AssetId, Name);
	LoadedTypes.Add(AssetId, TEXT("IMAGERY"));
	Message(TEXT("Added ") + Name + TEXT(" over the Cesium terrain."));
	if (Panel)
	{
		Panel->Refresh();
	}
	return Overlay;
}

ACesium3DTileset* ACesiumIonVrtakActor::AddTileset(int64 AssetId, const FString& Name, const FString& Type)
{
	if (!Georeference || Tilesets.Contains(AssetId))
	{
		return nullptr;
	}
	FActorSpawnParameters Params;
	Params.ObjectFlags |= RF_Transient;
	ACesium3DTileset* Tileset = GetWorld()->SpawnActor<ACesium3DTileset>(Params);
	if (!Tileset)
	{
		return nullptr;
	}
	// Children of the georeference follow its transform, which carries VR-TAK's frame.
	Tileset->AttachToActor(Georeference, FAttachmentTransformRules::KeepRelativeTransform);
	if (TestRaiseMetres != 0.0)
	{
		// Up in the georeference's frame (centimetres before VR-TAK's scale).
		Tileset->SetActorRelativeLocation(FVector(0.0, 0.0, TestRaiseMetres * 100.0));
	}
	Tileset->SetGeoreference(Georeference);
	Tileset->SetTilesetSource(ETilesetSource::FromCesiumIon);
	if (SelfHostedServer)
	{
		Tileset->SetCesiumIonServer(SelfHostedServer);
	}
	Tileset->SetIonAccessToken(Session->GetToken());
	Tileset->SetIonAssetID(AssetId);
	Tilesets.Add(AssetId, Tileset);
	LoadedNames.Add(AssetId, Name);
	LoadedTypes.Add(AssetId, Type);
	if (Type == TEXT("TERRAIN"))
	{
		UpdateVrtakTerrain();
	}
	Message(TEXT("Added ") + Name + TEXT(" to VR-TAK's world."));
	if (Panel)
	{
		Panel->Refresh();
	}
	return Tileset;
}
