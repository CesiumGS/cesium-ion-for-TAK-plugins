#pragma once

#include "CoreMinimal.h"
#include "GameFramework/Actor.h"
#include "UObject/WeakObjectPtr.h"
#include "CesiumIonVrtakActor.generated.h"

class ACesium3DTileset;
class ACesiumGeoreference;
class UCesiumIonRasterOverlay;
class AVH_GlobeReferenceActor;
class UCesiumIonServer;
class UCesiumIonVrtakPanel;
class UUserWidget;
class UCesiumIonVrtakSession;
struct FCesiumIonVrtakAsset;
struct FCesium3DTilesetLoadFailureDetails;
struct FCesiumRasterOverlayLoadFailureDetails;

/**
 * The plugin's actor in VR-TAK's world: the ion session, the panel, one
 * Cesium georeference kept aligned with VR-TAK's globe, and the 3D Tiles,
 * terrain and imagery the user has added (imagery drapes over Cesium terrain).
 *
 * Alignment: VR-TAK's globe (AVH_GlobeReferenceActor) maps geodetic
 * coordinates to Unreal space in its own frame (in ENU mode +X north, +Y
 * east, +Z up, scaled for tabletop views). The georeference takes the globe's
 * origin as its own and gets the Unreal transform measured from the globe
 * (east, north and up 100 m from the origin), so Cesium content lands where
 * VR-TAK puts the same coordinates. Re-measured when the globe moves.
 *
 * Test switches: -CesiumIonLoad=<id,id,...> adds assets at start, in order
 * (terrain before the imagery that drapes over it);
 * -CesiumIonOrigin=<lat,lon[,height]> moves VR-TAK's area of operations there
 * (in the TOC, before entering the world); -CesiumIonCamera=<lat,lon,height,
 * bearing,pitch> places the view in the world (default: 1 km south of the
 * origin, 350 m up, looking north and 35 degrees down);
 * -CesiumIonScreenshots=<seconds> saves three screenshots with the UI
 * (Saved/Screenshots/CesiumIon-1..3.png) that many seconds after start and
 * 30 s apart; -CesiumIonQuitAfter=<seconds> then closes VR-TAK;
 * -CesiumIonEnterWorld=<seconds> leaves the start-up room for the world then,
 * as VR-TAK's "Enter World" ribbon button does (console command ce GoToWorld),
 * re-applies the origin there and answers the first-start tutorial prompt;
 * -CesiumIonShowSettings=<seconds> opens the settings section then (25 s later
 * closed); -CesiumIonRaise=<metres> lifts Cesium layers that much (to tell content
 * hidden under VR-TAK's own ground from content that is missing); -CesiumIonSignIn starts "Sign in with Cesium ion" (opens ion's page
 * in the browser; the person signs in, a test never sets a token).
 */
UCLASS()
class ACesiumIonVrtakActor : public AActor
{
	GENERATED_BODY()

public:
	ACesiumIonVrtakActor();

	UCesiumIonVrtakSession* GetSession() const { return Session; }
	bool IsLoaded(int64 AssetId) const;
	/** Names of the assets on the map, by ID. */
	TArray<TPair<int64, FString>> GetLoaded() const;
	/** ion's type of an asset on the map (3DTILES, TERRAIN or IMAGERY). */
	FString GetLoadedType(int64 AssetId) const;
	/** True when VR-TAK can show this ion asset type. */
	static bool IsSupportedType(const FString& Type);
	/**
	 * Adds a 3D Tiles, terrain or imagery asset (imagery over the Cesium terrain
	 * on the map), or removes it when it is on the map.
	 */
	void ToggleAsset(const FCesiumIonVrtakAsset& Asset);
	void SignInOrOut();
	/** Saves the server (panel settings) and lists the new server's assets. */
	void SaveServerSettings(bool bSelfHosted, const FString& ServerUrl, const FString& ApiUrl, bool bSingleUser);
	void RefreshAssets();

	virtual void Tick(float DeltaSeconds) override;

protected:
	virtual void BeginPlay() override;
	virtual void EndPlay(const EEndPlayReason::Type Reason) override;

private:
	void OpenPanel();
	void Message(const FString& Text);
	AVH_GlobeReferenceActor* FindGlobe() const;
	/** Aligns the georeference with VR-TAK's globe; true when it changed. */
	bool SyncGeoreference();
	ACesium3DTileset* AddTileset(int64 AssetId, const FString& Name, const FString& Type);
	/** Drapes an ion imagery asset over the Cesium terrain on the map. */
	UCesiumIonRasterOverlay* AddImagery(int64 AssetId, const FString& Name);
	/** Forgets imagery whose terrain was removed. */
	void ForgetOrphanedImagery();
	/**
	 * VR-TAK draws its own map (Game_TileManager) over Cesium terrain near the
	 * viewer. While Cesium terrain is in the world, turns VR-TAK's map off the
	 * way VR-TAK's own terrain setting does (SetEnabled), and back on when the
	 * last Cesium terrain goes or the plugin shuts down, if it turned it off.
	 */
	void UpdateVrtakTerrain(bool bShuttingDown = false);
	void ApplyTestSwitches();
	/**
	 * Moves the player the way VR-TAK's Go To does: the pawn's TeleportToCoordinate
	 * (called by reflection), which in the TOC moves VR-TAK's area of operations and
	 * in the world places the player. Optionally turns the view (bearing, pitch).
	 */
	bool TeleportPlayer(double Latitude, double Longitude, double Height, double Bearing, double Pitch, bool bTurn);
	/** Keeps the panel on screen (VR-TAK may clear viewport widgets while it starts). */
	void EnsurePanel();
	/** Places the panel top right, below VR-TAK's ribbon. */
	void PlacePanel();
	/** Logs Cesium's load failures for this plugin's layers (HTTP status and message). */
	void OnTilesetLoadFailure(const FCesium3DTilesetLoadFailureDetails& Details);
	void OnOverlayLoadFailure(const FCesiumRasterOverlayLoadFailureDetails& Details);
	/** Logs each layer's load progress and where its loaded tiles are, next to the camera. */
	void LogLayerDiagnostics();
	/** Test runs: answers VR-TAK's first-start tutorial prompt with "No", as a user would. */
	bool DismissTutorialPrompt();

	UPROPERTY()
	UCesiumIonVrtakSession* Session = nullptr;
	UPROPERTY()
	UCesiumIonVrtakPanel* Panel = nullptr;
	UPROPERTY()
	ACesiumGeoreference* Georeference = nullptr;
	UPROPERTY()
	UCesiumIonServer* SelfHostedServer = nullptr;
	UPROPERTY()
	TMap<int64, ACesium3DTileset*> Tilesets;
	/** Imagery on the map; a component of the terrain tileset it drapes over. */
	TMap<int64, TWeakObjectPtr<UCesiumIonRasterOverlay>> Overlays;
	TMap<int64, FString> LoadedNames;
	TMap<int64, FString> LoadedTypes;

	FTransform LastGlobeTransform;
	TWeakObjectPtr<AVH_GlobeReferenceActor> LastSyncedGlobe;
	FVector LastOrigin = FVector::ZeroVector;
	bool bSynced = false;
	float SyncCountdown = 0.f;
	TArray<int64> PendingTestLoads;
	double ElapsedSeconds = 0.0;
	double ScreenshotAt = -1.0;
	int32 ScreenshotsTaken = 0;
	bool bScreenshotsWaitForSignIn = false;
	bool bTurnedOffVrtakTerrain = false;
	TWeakObjectPtr<UObject> TileManagerCache;
	double QuitAt = -1.0;
	double TestRaiseMetres = 0.0;
	double NextDiagnosticsAt = 30.0;
	int32 DiagnosticsLogged = 0;
	FDelegateHandle TilesetFailureHandle;
	FDelegateHandle OverlayFailureHandle;
	double EnterWorldAt = -1.0;
	double ShowSettingsAt = -1.0;
	/** When the headset state is next written to the log (see Tick), and the last one written. */
	double NextXrLogAt = 0.0;
	FString LastXrState;

	// --- Ribbon button and headset panel
	// The panel opens from a "Cesium ion" button in VR-TAK's ribbon. On the desktop it is a screen
	// widget; in VR it opens through VR-TAK's UI subsystem as a world-space panel (a screen widget
	// is not drawn in a headset). VR-TAK's classes are reached by name (reflection), as for the
	// teleport, so the plugin does not link VR-TAK's Game module.
public:
	/** The panel is the plugin's own floating panel (VR). */
	bool IsPanelFloating() const { return PanelHolder != nullptr; }
	bool IsFollowingPanel() const { return bFollowPanel; }
	/** Moves the view to look at a loaded 3D Tiles asset from outside it. */
	void GoToAsset(int64 AssetId);
	/** Takes the panel off the screen (or out of the world); the ribbon button brings it back. */
	void HidePanel();
	/** Puts the floating panel 120 cm from the camera, YawDegrees to the side of where it looks. */
	void DockPanel(double YawDegrees);
	void ToggleFollowPanel();
	void ScalePanel(float Factor);
	/** Bound to the ribbon button's OnClicked: shows or hides the panel. */
	UFUNCTION()
	void HandleRibbonButtonClicked(UUserWidget* Button);

private:
	void RegisterRibbonButton();
	bool OpenPanelInWorld();
	void ClosePanelInWorld();
	bool OpenPanelAsWidgetComponent();
	void MovePanelForView(bool bVr);
	UObject* FindWorldSubsystem(const TCHAR* ClassPath) const;
	UObject* FindGameInstanceSubsystem(const TCHAR* ClassPath) const;

	UPROPERTY()
	UUserWidget* RibbonButton = nullptr;
	/** The actor holding the plugin's own floating panel (when VR-TAK's UI system does not open it). */
	UPROPERTY()
	AActor* PanelHolder = nullptr;
	/** PanelHolder is the plugin's own actor (destroyed with the panel), not VR-TAK's world UI actor. */
	bool bOwnPanelHolder = false;
	bool bRibbonButtonRegistered = false;
	int32 RibbonTries = 0;
	double NextRibbonTryAt = 5.0;
	/** The panel is a world-space panel (VR) rather than a screen widget. */
	bool bPanelInWorld = false;
	/** False after the person closed the panel with the ribbon button: it is not reopened by itself. */
	bool bPanelWanted = true;
	bool bWasStereo = false;
	bool bFollowPanel = false;
	double PanelYawOffset = 0.0;
	float PanelScale = 0.1f;
	double HideSettingsAt = -1.0;
	bool bStartSignIn = false;
	double EnteredWorldAt = -1.0;
	bool bTutorialDismissed = false;
	bool bAnswerTutorialPrompt = false;
	bool bPanelDiagnosed = false;
	FVector2D PanelPosition = FVector2D::ZeroVector;
	FVector2D PanelSize = FVector2D::ZeroVector;
	TArray<double> TestOrigin;
	bool bTeleportedInTOC = false;
	/** The globe last aligned with (logged when it changes). */
	mutable TWeakObjectPtr<AVH_GlobeReferenceActor> LastGlobe;
	TArray<double> TestCamera;
	bool bPanelRestoreLogged = false;
};
